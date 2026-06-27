package badapodo.sugang.service.inmemory;

import badapodo.sugang.exception.ApplicationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class InMemorySingleWriterEnrollmentService {

    private final InMemoryCourseStateStore courseStateStore;
    private final InMemoryEnrollmentWriteBehindService writeBehindService;
    private final int partitionCount;
    private final int queueCapacityPerPartition;
    private final long responseTimeoutMs;
    private final List<BlockingQueue<InMemoryEnrollmentCommand>> queues;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Counter enqueuedCounter;
    private final Counter processedCounter;
    private final Counter rejectedCounter;
    private final Timer matchLatencyTimer;
    private final Timer responseLatencyTimer;
    private ExecutorService executorService;

    public InMemorySingleWriterEnrollmentService(
            InMemoryCourseStateStore courseStateStore,
            InMemoryEnrollmentWriteBehindService writeBehindService,
            MeterRegistry meterRegistry,
            @Value("${IN_MEMORY_SINGLE_WRITER_PARTITION_COUNT:8}") int partitionCount,
            @Value("${IN_MEMORY_SINGLE_WRITER_QUEUE_CAPACITY_PER_PARTITION:10000}") int queueCapacityPerPartition,
            @Value("${IN_MEMORY_SINGLE_WRITER_RESPONSE_TIMEOUT_MS:5000}") long responseTimeoutMs
    ) {
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("IN_MEMORY_SINGLE_WRITER_PARTITION_COUNT must be greater than 0");
        }
        if (queueCapacityPerPartition <= 0) {
            throw new IllegalArgumentException("IN_MEMORY_SINGLE_WRITER_QUEUE_CAPACITY_PER_PARTITION must be greater than 0");
        }
        if (responseTimeoutMs <= 0) {
            throw new IllegalArgumentException("IN_MEMORY_SINGLE_WRITER_RESPONSE_TIMEOUT_MS must be greater than 0");
        }

        this.courseStateStore = courseStateStore;
        this.writeBehindService = writeBehindService;
        this.partitionCount = partitionCount;
        this.queueCapacityPerPartition = queueCapacityPerPartition;
        this.responseTimeoutMs = responseTimeoutMs;
        this.queues = createQueues(partitionCount, queueCapacityPerPartition);
        this.enqueuedCounter = Counter.builder("in_memory_single_writer.enqueued.count").register(meterRegistry);
        this.processedCounter = Counter.builder("in_memory_single_writer.processed.count").register(meterRegistry);
        this.rejectedCounter = Counter.builder("in_memory_single_writer.rejected.count").register(meterRegistry);
        this.matchLatencyTimer = Timer.builder("in_memory_single_writer.match.latency").register(meterRegistry);
        this.responseLatencyTimer = Timer.builder("in_memory_single_writer.response.latency").register(meterRegistry);

        Gauge.builder(
                        "in_memory_single_writer.queue.depth",
                        queues,
                        queueList -> queueList.stream().mapToInt(BlockingQueue::size).sum()
                )
                .register(meterRegistry);
    }

    @PostConstruct
    public void startWorkers() {
        executorService = Executors.newFixedThreadPool(partitionCount);
        for (int partitionIndex = 0; partitionIndex < partitionCount; partitionIndex++) {
            int workerPartitionIndex = partitionIndex;
            executorService.submit(() -> consumePartition(workerPartitionIndex));
        }
        log.info(
                "Started in-memory single-writer workers. partitionCount={}, queueCapacityPerPartition={}",
                partitionCount,
                queueCapacityPerPartition
        );
    }

    @PreDestroy
    public void stopWorkers() {
        running.set(false);
        if (executorService == null) {
            return;
        }

        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("In-memory single-writer workers did not terminate within timeout.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public InMemoryEnrollmentResponse enroll(Long studentId, Long courseId, String scenarioType) {
        InMemoryEnrollmentCommand command = InMemoryEnrollmentCommand.create(studentId, courseId, scenarioType);
        int partitionIndex = partitionIndex(courseId);
        Instant responseStartedAt = Instant.now();

        boolean accepted = queues.get(partitionIndex).offer(command);
        if (!accepted) {
            rejectedCounter.increment();
            responseLatencyTimer.record(Duration.between(responseStartedAt, Instant.now()));
            return InMemoryEnrollmentResponse.queueFull(command.getCommandId(), partitionIndex);
        }

        enqueuedCounter.increment();

        try {
            InMemoryEnrollmentResult result = command.getResponseFuture().get(responseTimeoutMs, TimeUnit.MILLISECONDS);
            return InMemoryEnrollmentResponse.completed(command.getCommandId(), partitionIndex, result);
        } catch (TimeoutException e) {
            return InMemoryEnrollmentResponse.timeout(command.getCommandId(), partitionIndex);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return InMemoryEnrollmentResponse.completed(
                    command.getCommandId(),
                    partitionIndex,
                    InMemoryEnrollmentResult.failure(new IllegalStateException("응답 대기 중 인터럽트가 발생했습니다.", e))
            );
        } catch (ExecutionException e) {
            return InMemoryEnrollmentResponse.completed(
                    command.getCommandId(),
                    partitionIndex,
                    InMemoryEnrollmentResult.failure(new IllegalStateException("응답 대기 중 오류가 발생했습니다.", e))
            );
        } finally {
            responseLatencyTimer.record(Duration.between(responseStartedAt, Instant.now()));
        }
    }

    public int partitionIndex(Long courseId) {
        return Math.floorMod(courseId.hashCode(), partitionCount);
    }

    private void consumePartition(int partitionIndex) {
        BlockingQueue<InMemoryEnrollmentCommand> queue = queues.get(partitionIndex);

        while (running.get() || !queue.isEmpty()) {
            try {
                InMemoryEnrollmentCommand command = queue.poll(500, TimeUnit.MILLISECONDS);
                if (command == null) {
                    continue;
                }

                processCommand(partitionIndex, command);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void processCommand(int partitionIndex, InMemoryEnrollmentCommand command) {
        Instant matchStartedAt = Instant.now();
        try {
            courseStateStore.validateAndEnroll(command.getStudentId(), command.getCourseId());
            writeBehindService.enqueue(InMemoryEnrollmentWriteBehindEvent.of(
                    command.getCommandId(),
                    command.getStudentId(),
                    command.getCourseId(),
                    Instant.now()
            ));

            processedCounter.increment();
            command.complete(InMemoryEnrollmentResult.success());
        } catch (ApplicationException e) {
            processedCounter.increment();
            command.complete(InMemoryEnrollmentResult.failure(e));
        } catch (RuntimeException e) {
            processedCounter.increment();
            command.complete(InMemoryEnrollmentResult.failure(e));
            log.warn(
                    "In-memory single-writer processing failure. partition={}, commandId={}, reason={}",
                    partitionIndex,
                    command.getCommandId(),
                    e.getClass().getSimpleName(),
                    e
            );
        } finally {
            matchLatencyTimer.record(Duration.between(matchStartedAt, Instant.now()));
        }
    }

    private List<BlockingQueue<InMemoryEnrollmentCommand>> createQueues(
            int partitionCount,
            int queueCapacityPerPartition
    ) {
        List<BlockingQueue<InMemoryEnrollmentCommand>> createdQueues = new ArrayList<>();
        for (int partitionIndex = 0; partitionIndex < partitionCount; partitionIndex++) {
            createdQueues.add(new ArrayBlockingQueue<>(queueCapacityPerPartition));
        }
        return createdQueues;
    }
}
