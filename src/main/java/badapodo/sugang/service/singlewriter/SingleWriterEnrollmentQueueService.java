package badapodo.sugang.service.singlewriter;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SingleWriterEnrollmentQueueService {

    private static final String QUEUE_FULL_REASON = "QueueFullException";
    private static final List<String> KNOWN_DOMAIN_FAILURE_REASONS = List.of(
            "CapacityExcessException",
            "DuplicateEnrollmentException",
            "AlreadyCompletedCourseException",
            "PrerequisiteNotMetException",
            "TimeConflictException",
            QUEUE_FULL_REASON
    );

    private final SingleWriterEnrollmentProcessor processor;
    private final MeterRegistry meterRegistry;
    private final int partitionCount;
    private final int queueCapacityPerPartition;
    private final List<BlockingQueue<SingleWriterEnrollmentCommand>> queues;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Counter enqueuedCounter;
    private final Counter rejectedCounter;
    private final Counter processedCounter;
    private final Counter failedCounter;
    private final Timer processingLatencyTimer;
    private final Timer endToEndLatencyTimer;
    private ExecutorService executorService;

    public SingleWriterEnrollmentQueueService(
            SingleWriterEnrollmentProcessor processor,
            MeterRegistry meterRegistry,
            @Value("${SINGLE_WRITER_PARTITION_COUNT:8}") int partitionCount,
            @Value("${SINGLE_WRITER_QUEUE_CAPACITY_PER_PARTITION:10000}") int queueCapacityPerPartition
    ) {
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("SINGLE_WRITER_PARTITION_COUNT must be greater than 0");
        }
        if (queueCapacityPerPartition <= 0) {
            throw new IllegalArgumentException("SINGLE_WRITER_QUEUE_CAPACITY_PER_PARTITION must be greater than 0");
        }

        this.processor = processor;
        this.meterRegistry = meterRegistry;
        this.partitionCount = partitionCount;
        this.queueCapacityPerPartition = queueCapacityPerPartition;
        this.queues = createQueues(partitionCount, queueCapacityPerPartition);
        this.enqueuedCounter = Counter.builder("single_writer.enqueued.count").register(meterRegistry);
        this.rejectedCounter = Counter.builder("single_writer.rejected.count").register(meterRegistry);
        this.processedCounter = Counter.builder("single_writer.processed.count").register(meterRegistry);
        this.failedCounter = Counter.builder("single_writer.failed.count").register(meterRegistry);
        this.processingLatencyTimer = Timer.builder("single_writer.processing.latency").register(meterRegistry);
        this.endToEndLatencyTimer = Timer.builder("single_writer.end_to_end.latency").register(meterRegistry);

        registerQueueDepthGauges();
        registerDomainFailureCounters();
    }

    @PostConstruct
    public void startWorkers() {
        executorService = Executors.newFixedThreadPool(partitionCount);
        for (int partitionIndex = 0; partitionIndex < partitionCount; partitionIndex++) {
            int workerPartitionIndex = partitionIndex;
            executorService.submit(() -> consumePartition(workerPartitionIndex));
        }
        log.info(
                "Started single-writer workers. partitionCount={}, queueCapacityPerPartition={}",
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
                log.warn("Single-writer workers did not terminate within timeout.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public SingleWriterEnqueueResult enqueue(Long studentId, Long courseId, String scenarioType) {
        SingleWriterEnrollmentCommand command = SingleWriterEnrollmentCommand.create(studentId, courseId, scenarioType);
        int partitionIndex = partitionIndex(courseId);
        boolean accepted = queues.get(partitionIndex).offer(command);

        if (accepted) {
            enqueuedCounter.increment();
            return SingleWriterEnqueueResult.of(true, command.getCommandId(), partitionIndex);
        }

        rejectedCounter.increment();
        meterRegistry.counter("single_writer.domain_failure.count", "reason", QUEUE_FULL_REASON).increment();
        return SingleWriterEnqueueResult.of(false, command.getCommandId(), partitionIndex);
    }

    public int partitionIndex(Long courseId) {
        return Math.floorMod(courseId.hashCode(), partitionCount);
    }

    private void consumePartition(int partitionIndex) {
        BlockingQueue<SingleWriterEnrollmentCommand> queue = queues.get(partitionIndex);

        while (running.get() || !queue.isEmpty()) {
            try {
                SingleWriterEnrollmentCommand command = queue.poll(500, TimeUnit.MILLISECONDS);
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

    private void processCommand(int partitionIndex, SingleWriterEnrollmentCommand command) {
        Instant processingStartedAt = Instant.now();
        try {
            processor.process(command);
            processedCounter.increment();
        } catch (ApplicationException e) {
            failedCounter.increment();
            meterRegistry.counter("single_writer.domain_failure.count", "reason", e.getClass().getSimpleName())
                    .increment();
            log.debug(
                    "Single-writer domain failure. partition={}, commandId={}, reason={}, message={}",
                    partitionIndex,
                    command.getCommandId(),
                    e.getClass().getSimpleName(),
                    e.getMessage()
            );
        } catch (RuntimeException e) {
            failedCounter.increment();
            meterRegistry.counter("single_writer.domain_failure.count", "reason", e.getClass().getSimpleName())
                    .increment();
            log.warn(
                    "Single-writer processing failure. partition={}, commandId={}, reason={}",
                    partitionIndex,
                    command.getCommandId(),
                    e.getClass().getSimpleName(),
                    e
            );
        } finally {
            processingLatencyTimer.record(Duration.between(processingStartedAt, Instant.now()));
            endToEndLatencyTimer.record(Duration.between(command.getEnqueuedAt(), Instant.now()));
        }
    }

    private List<BlockingQueue<SingleWriterEnrollmentCommand>> createQueues(
            int partitionCount,
            int queueCapacityPerPartition
    ) {
        List<BlockingQueue<SingleWriterEnrollmentCommand>> createdQueues = new ArrayList<>();
        for (int partitionIndex = 0; partitionIndex < partitionCount; partitionIndex++) {
            createdQueues.add(new ArrayBlockingQueue<>(queueCapacityPerPartition));
        }
        return createdQueues;
    }

    private void registerQueueDepthGauges() {
        Gauge.builder(
                        "single_writer.queue.depth",
                        queues,
                        queueList -> queueList.stream().mapToInt(BlockingQueue::size).sum()
                )
                .register(meterRegistry);

        for (int partitionIndex = 0; partitionIndex < queues.size(); partitionIndex++) {
            Gauge.builder(
                            "single_writer.partition.queue.depth",
                            queues.get(partitionIndex),
                            BlockingQueue::size
                    )
                    .tag("partition", String.valueOf(partitionIndex))
                    .register(meterRegistry);
        }
    }

    private void registerDomainFailureCounters() {
        for (String reason : KNOWN_DOMAIN_FAILURE_REASONS) {
            meterRegistry.counter("single_writer.domain_failure.count", "reason", reason);
        }
    }
}
