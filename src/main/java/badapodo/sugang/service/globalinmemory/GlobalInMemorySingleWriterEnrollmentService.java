package badapodo.sugang.service.globalinmemory;

import badapodo.sugang.exception.ApplicationException;
import badapodo.sugang.service.inmemory.InMemoryEnrollmentResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
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
public class GlobalInMemorySingleWriterEnrollmentService {

    private final GlobalInMemoryStateStore stateStore;
    private final GlobalInMemoryWriteBehindService writeBehindService;
    private final long responseTimeoutMs;
    private final BlockingQueue<GlobalInMemoryEnrollmentCommand> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Counter enqueuedCounter;
    private final Counter rejectedCounter;
    private final Counter processedCounter;
    private final Counter failedCounter;
    private final MeterRegistry meterRegistry;
    private final Timer matchLatencyTimer;
    private final Timer responseLatencyTimer;
    private ExecutorService writerExecutor;

    public GlobalInMemorySingleWriterEnrollmentService(
            GlobalInMemoryStateStore stateStore,
            GlobalInMemoryWriteBehindService writeBehindService,
            MeterRegistry meterRegistry,
            @Value("${GLOBAL_SINGLE_WRITER_QUEUE_CAPACITY:100000}") int queueCapacity,
            @Value("${GLOBAL_SINGLE_WRITER_RESPONSE_TIMEOUT_MS:5000}") long responseTimeoutMs
    ) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("GLOBAL_SINGLE_WRITER_QUEUE_CAPACITY must be greater than 0");
        }
        if (responseTimeoutMs <= 0) {
            throw new IllegalArgumentException("GLOBAL_SINGLE_WRITER_RESPONSE_TIMEOUT_MS must be greater than 0");
        }

        this.stateStore = stateStore;
        this.writeBehindService = writeBehindService;
        this.responseTimeoutMs = responseTimeoutMs;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.meterRegistry = meterRegistry;
        this.enqueuedCounter = Counter.builder("global_single_writer.enqueued.count").register(meterRegistry);
        this.rejectedCounter = Counter.builder("global_single_writer.rejected.count").register(meterRegistry);
        this.processedCounter = Counter.builder("global_single_writer.processed.count").register(meterRegistry);
        this.failedCounter = Counter.builder("global_single_writer.failed.count").register(meterRegistry);
        this.matchLatencyTimer = Timer.builder("global_single_writer.match.latency").register(meterRegistry);
        this.responseLatencyTimer = Timer.builder("global_single_writer.response.latency").register(meterRegistry);
        Gauge.builder("global_single_writer.queue.depth", queue, BlockingQueue::size).register(meterRegistry);
    }

    @PostConstruct
    public void startWriter() {
        writerExecutor = Executors.newSingleThreadExecutor();
        writerExecutor.submit(this::consume);
        log.info("Started global in-memory single writer.");
    }

    @PreDestroy
    public void stopWriter() {
        running.set(false);
        if (writerExecutor == null) {
            return;
        }
        writerExecutor.shutdownNow();
        try {
            if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Global in-memory single writer did not terminate within timeout.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public GlobalInMemoryEnrollmentResponse enroll(Long studentId, Long courseId, String scenarioType) {
        GlobalInMemoryEnrollmentCommand command = GlobalInMemoryEnrollmentCommand.create(
                studentId,
                courseId,
                scenarioType
        );
        Instant responseStartedAt = Instant.now();
        if (!queue.offer(command)) {
            rejectedCounter.increment();
            responseLatencyTimer.record(Duration.between(responseStartedAt, Instant.now()));
            return GlobalInMemoryEnrollmentResponse.queueFull(command.getCommandId());
        }
        enqueuedCounter.increment();

        try {
            InMemoryEnrollmentResult result = command.getResponseFuture().get(responseTimeoutMs, TimeUnit.MILLISECONDS);
            return GlobalInMemoryEnrollmentResponse.completed(command.getCommandId(), result);
        } catch (TimeoutException e) {
            return GlobalInMemoryEnrollmentResponse.timeout(command.getCommandId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GlobalInMemoryEnrollmentResponse.completed(
                    command.getCommandId(),
                    InMemoryEnrollmentResult.failure(new IllegalStateException("응답 대기 중 인터럽트가 발생했습니다.", e))
            );
        } catch (ExecutionException e) {
            return GlobalInMemoryEnrollmentResponse.completed(
                    command.getCommandId(),
                    InMemoryEnrollmentResult.failure(new IllegalStateException("응답 대기 중 오류가 발생했습니다.", e))
            );
        } finally {
            responseLatencyTimer.record(Duration.between(responseStartedAt, Instant.now()));
        }
    }

    private void consume() {
        while (running.get() || !queue.isEmpty()) {
            try {
                GlobalInMemoryEnrollmentCommand command = queue.poll(500, TimeUnit.MILLISECONDS);
                if (command != null) {
                    process(command);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void process(GlobalInMemoryEnrollmentCommand command) {
        Instant matchStartedAt = Instant.now();
        try {
            stateStore.validateAndEnroll(command.getStudentId(), command.getCourseId());
            writeBehindService.enqueue(GlobalInMemoryWriteBehindEvent.of(
                    command.getCommandId(),
                    command.getStudentId(),
                    command.getCourseId(),
                    Instant.now()
            ));
            processedCounter.increment();
            command.complete(InMemoryEnrollmentResult.success());
        } catch (ApplicationException e) {
            processedCounter.increment();
            failedCounter.increment();
            Counter.builder("global_single_writer.domain_failure.count")
                    .tag("reason", e.getClass().getSimpleName())
                    .register(meterRegistry)
                    .increment();
            command.complete(InMemoryEnrollmentResult.failure(e));
        } catch (RuntimeException e) {
            processedCounter.increment();
            failedCounter.increment();
            command.complete(InMemoryEnrollmentResult.failure(e));
            log.warn(
                    "Global in-memory writer failure. commandId={}, reason={}",
                    command.getCommandId(),
                    e.getClass().getSimpleName(),
                    e
            );
        } finally {
            matchLatencyTimer.record(Duration.between(matchStartedAt, Instant.now()));
        }
    }
}
