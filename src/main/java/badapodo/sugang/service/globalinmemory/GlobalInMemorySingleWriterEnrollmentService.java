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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Counter successCounter;
    private final Counter timeoutCounter;
    private final Counter duplicateCommandProcessedCounter;
    private final Counter duplicateSuccessPairCounter;
    private final MeterRegistry meterRegistry;
    private final Timer matchLatencyTimer;
    private final Timer responseWaitLatencyTimer;
    private final Timer commandLagTimer;
    private final AtomicInteger inflightRequests = new AtomicInteger();
    private final Set<String> processedCommandIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> successfulPairCommandIds = new ConcurrentHashMap<>();
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
        this.enqueuedCounter = Counter.builder("global_single_writer_enqueued").register(meterRegistry);
        this.rejectedCounter = Counter.builder("global_single_writer_rejected").register(meterRegistry);
        this.processedCounter = Counter.builder("global_single_writer_processed").register(meterRegistry);
        this.failedCounter = Counter.builder("global_single_writer_failed").register(meterRegistry);
        this.successCounter = Counter.builder("global_single_writer_success").register(meterRegistry);
        this.timeoutCounter = Counter.builder("global_single_writer_timeout").register(meterRegistry);
        this.duplicateCommandProcessedCounter = Counter.builder("global_single_writer_duplicate_command_processed")
                .register(meterRegistry);
        this.duplicateSuccessPairCounter = Counter.builder("global_single_writer_duplicate_success_pair")
                .register(meterRegistry);
        this.matchLatencyTimer = histogramTimer("global_single_writer_match_latency", meterRegistry);
        this.responseWaitLatencyTimer = histogramTimer(
                "global_single_writer_response_wait_latency",
                meterRegistry
        );
        this.commandLagTimer = histogramTimer("global_single_writer_command_lag", meterRegistry);
        Gauge.builder("global_single_writer_queue_depth", queue, BlockingQueue::size).register(meterRegistry);
        Gauge.builder("global_single_writer_inflight_requests", inflightRequests, AtomicInteger::get)
                .register(meterRegistry);
        Gauge.builder(
                        "global_single_writer_write_behind_enqueue_gap",
                        successCounter,
                        counter -> counter.count() - writeBehindService.enqueuedCount()
                )
                .register(meterRegistry);
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
        Instant responseStartedAt = Instant.now();
        GlobalInMemoryEnrollmentSubmission submission = submit(
                studentId,
                courseId,
                scenarioType
        );
        GlobalInMemoryEnrollmentCommand command = submission.getCommand();
        if (!submission.isAccepted()) {
            responseWaitLatencyTimer.record(Duration.between(responseStartedAt, Instant.now()));
            return GlobalInMemoryEnrollmentResponse.queueFull(command.getCommandId());
        }
        inflightRequests.incrementAndGet();

        try {
            InMemoryEnrollmentResult result = command.getResponseFuture().get(responseTimeoutMs, TimeUnit.MILLISECONDS);
            return GlobalInMemoryEnrollmentResponse.completed(command.getCommandId(), result);
        } catch (TimeoutException e) {
            timeoutCounter.increment();
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
            inflightRequests.decrementAndGet();
            responseWaitLatencyTimer.record(Duration.between(responseStartedAt, Instant.now()));
        }
    }

    public GlobalInMemoryEnrollmentSubmission submit(Long studentId, Long courseId, String scenarioType) {
        GlobalInMemoryEnrollmentCommand command = GlobalInMemoryEnrollmentCommand.create(
                studentId,
                courseId,
                scenarioType
        );
        if (!queue.offer(command)) {
            rejectedCounter.increment();
            return GlobalInMemoryEnrollmentSubmission.rejected(command);
        }
        enqueuedCounter.increment();
        return GlobalInMemoryEnrollmentSubmission.accepted(command);
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
        commandLagTimer.record(Duration.between(command.getEnqueuedAt(), matchStartedAt));
        if (!processedCommandIds.add(command.getCommandId())) {
            duplicateCommandProcessedCounter.increment();
            failedCounter.increment();
            log.error(
                    "Global writer duplicate command processing blocked. commandId={}, studentId={}, courseId={}, scenarioType={}",
                    command.getCommandId(),
                    command.getStudentId(),
                    command.getCourseId(),
                    command.getScenarioType()
            );
            command.complete(InMemoryEnrollmentResult.failure(
                    new IllegalStateException("동일한 command가 두 번 처리되었습니다.")
            ));
            return;
        }

        boolean alreadyExistsInMemoryBeforeSuccess = stateStore.isEnrolled(
                command.getStudentId(),
                command.getCourseId()
        );
        try {
            stateStore.validateAndEnroll(command.getStudentId(), command.getCourseId());
            String pairKey = command.getStudentId() + ":" + command.getCourseId();
            String previousCommandId = successfulPairCommandIds.putIfAbsent(pairKey, command.getCommandId());
            if (previousCommandId != null) {
                duplicateSuccessPairCounter.increment();
                log.error(
                        "Global writer duplicate successful pair detected. commandId={}, previousCommandId={}, "
                                + "studentId={}, courseId={}, scenarioType={}, alreadyExistsInMemoryBeforeSuccess={}",
                        command.getCommandId(),
                        previousCommandId,
                        command.getStudentId(),
                        command.getCourseId(),
                        command.getScenarioType(),
                        alreadyExistsInMemoryBeforeSuccess
                );
            }
            writeBehindService.enqueue(GlobalInMemoryWriteBehindEvent.of(
                    command.getCommandId(),
                    command.getStudentId(),
                    command.getCourseId(),
                    command.getScenarioType(),
                    "SUCCESS",
                    alreadyExistsInMemoryBeforeSuccess,
                    1,
                    matchStartedAt,
                    Instant.now()
            ));
            processedCounter.increment();
            successCounter.increment();
            command.complete(InMemoryEnrollmentResult.success());
        } catch (ApplicationException e) {
            processedCounter.increment();
            failedCounter.increment();
            Counter.builder("global_single_writer_domain_failure")
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

    private Timer histogramTimer(String name, MeterRegistry registry) {
        return Timer.builder(name)
                .publishPercentileHistogram()
                .register(registry);
    }
}
