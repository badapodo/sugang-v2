package badapodo.sugang.service.globalinmemory;

import badapodo.sugang.domain.Enrollment;
import badapodo.sugang.repository.EnrollmentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class GlobalInMemoryWriteBehindService {

    private final EnrollmentRepository enrollmentRepository;
    private final TransactionTemplate transactionTemplate;
    private final int workerCount;
    private final BlockingQueue<GlobalInMemoryWriteBehindEvent> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Counter enqueuedCounter;
    private final Counter processedCounter;
    private final Counter successCounter;
    private final Counter duplicateCounter;
    private final Counter duplicateCommandEnqueueCounter;
    private final Counter duplicateCommandProcessedCounter;
    private final MeterRegistry meterRegistry;
    private final Timer latencyTimer;
    private final Timer lagTimer;
    private final Set<String> enqueuedCommandIds = ConcurrentHashMap.newKeySet();
    private final Set<String> processedCommandIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger workerSequence = new AtomicInteger();
    private ExecutorService executorService;

    public GlobalInMemoryWriteBehindService(
            EnrollmentRepository enrollmentRepository,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry,
            @Value("${GLOBAL_SINGLE_WRITER_QUEUE_CAPACITY:100000}") int queueCapacity,
            @Value("${GLOBAL_SINGLE_WRITER_WRITE_BEHIND_WORKER_COUNT:4}") int workerCount
    ) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("GLOBAL_SINGLE_WRITER_QUEUE_CAPACITY must be greater than 0");
        }
        if (workerCount <= 0) {
            throw new IllegalArgumentException("GLOBAL_SINGLE_WRITER_WRITE_BEHIND_WORKER_COUNT must be greater than 0");
        }

        this.enrollmentRepository = enrollmentRepository;
        this.transactionTemplate = transactionTemplate;
        this.workerCount = workerCount;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.meterRegistry = meterRegistry;
        this.enqueuedCounter = Counter.builder("global_single_writer_write_behind_enqueued")
                .register(meterRegistry);
        this.processedCounter = Counter.builder("global_single_writer_write_behind_processed")
                .register(meterRegistry);
        this.successCounter = Counter.builder("global_single_writer_write_behind_success")
                .register(meterRegistry);
        this.duplicateCounter = Counter.builder("global_single_writer_write_behind_duplicate")
                .register(meterRegistry);
        this.duplicateCommandEnqueueCounter = Counter.builder(
                        "global_single_writer_write_behind_duplicate_command_enqueue"
                )
                .register(meterRegistry);
        this.duplicateCommandProcessedCounter = Counter.builder(
                        "global_single_writer_write_behind_duplicate_command_processed"
                )
                .register(meterRegistry);
        this.latencyTimer = histogramTimer("global_single_writer_write_behind_latency");
        this.lagTimer = histogramTimer("global_single_writer_write_behind_lag");
        Gauge.builder("global_single_writer_write_behind_queue_depth", queue, BlockingQueue::size)
                .register(meterRegistry);
    }

    @PostConstruct
    public void startWorkers() {
        executorService = Executors.newFixedThreadPool(
                workerCount,
                runnable -> new Thread(
                        runnable,
                        "global-write-behind-" + workerSequence.incrementAndGet()
                )
        );
        for (int index = 0; index < workerCount; index++) {
            executorService.submit(this::consume);
        }
        log.info("Started global in-memory write-behind workers. workerCount={}", workerCount);
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
                log.warn("Global in-memory write-behind workers did not terminate within timeout.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void enqueue(GlobalInMemoryWriteBehindEvent event) {
        if (!enqueuedCommandIds.add(event.getCommandId())) {
            duplicateCommandEnqueueCounter.increment();
            incrementFailure("DuplicateCommandEnqueue");
            log.error(
                    "Global write-behind duplicate command enqueue blocked. commandId={}, studentId={}, courseId={}, "
                            + "scenarioType={}, writerResult={}, alreadyExistsInMemoryBeforeSuccess={}, "
                            + "writeBehindAttemptNo={}, writeBehindWorkerName={}, existsInDatabaseBeforeInsert={}",
                    event.getCommandId(),
                    event.getStudentId(),
                    event.getCourseId(),
                    event.getScenarioType(),
                    event.getWriterResult(),
                    event.isAlreadyExistsInMemoryBeforeSuccess(),
                    event.getAttemptNo(),
                    Thread.currentThread().getName(),
                    "NOT_CHECKED"
            );
            throw new IllegalStateException("동일한 command가 write-behind queue에 두 번 들어왔습니다.");
        }
        try {
            queue.put(event);
            enqueuedCounter.increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("global write-behind enqueue was interrupted.", e);
        }
    }

    private void consume() {
        while (running.get() || !queue.isEmpty()) {
            try {
                GlobalInMemoryWriteBehindEvent event = queue.poll(500, TimeUnit.MILLISECONDS);
                if (event != null) {
                    processedCounter.increment();
                    if (!processedCommandIds.add(event.getCommandId())) {
                        duplicateCommandProcessedCounter.increment();
                        incrementFailure("DuplicateCommandProcessed");
                        logDuplicateFailure(
                                event,
                                "DuplicateCommandProcessed",
                                "NOT_CHECKED",
                                null
                        );
                        continue;
                    }
                    persist(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void persist(GlobalInMemoryWriteBehindEvent event) {
        Instant startedAt = Instant.now();
        lagTimer.record(Duration.between(event.getWriteBehindEnqueuedAt(), startedAt));
        AtomicBoolean existsBeforeInsert = new AtomicBoolean(false);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                boolean exists = enrollmentRepository.existsByStudentIdAndCourseId(
                        event.getStudentId(),
                        event.getCourseId()
                );
                existsBeforeInsert.set(exists);
                if (!exists) {
                    enrollmentRepository.saveAndFlush(Enrollment.create(
                            event.getStudentId(),
                            event.getCourseId()
                    ));
                }
            });
            if (existsBeforeInsert.get()) {
                duplicateCounter.increment();
                incrementFailure("AlreadyExistsBeforeInsert");
                logDuplicateFailure(event, "AlreadyExistsBeforeInsert", true, null);
                return;
            }
            successCounter.increment();
        } catch (DataIntegrityViolationException e) {
            duplicateCounter.increment();
            incrementFailure("DataIntegrityViolationException");
            logDuplicateFailure(event, "DataIntegrityViolationException", existsBeforeInsert.get(), e);
        } catch (RuntimeException e) {
            incrementFailure(e.getClass().getSimpleName());
            log.warn(
                    "Global write-behind insert failed. commandId={}, studentId={}, courseId={}, scenarioType={}, "
                            + "writerResult={}, alreadyExistsInMemoryBeforeSuccess={}, writeBehindAttemptNo={}, "
                            + "writeBehindWorkerName={}, existsInDatabaseBeforeInsert={}, reason={}",
                    event.getCommandId(),
                    event.getStudentId(),
                    event.getCourseId(),
                    event.getScenarioType(),
                    event.getWriterResult(),
                    event.isAlreadyExistsInMemoryBeforeSuccess(),
                    event.getAttemptNo(),
                    Thread.currentThread().getName(),
                    existsBeforeInsert.get(),
                    e.getClass().getSimpleName(),
                    e
            );
        } finally {
            latencyTimer.record(Duration.between(startedAt, Instant.now()));
        }
    }

    public double enqueuedCount() {
        return enqueuedCounter.count();
    }

    private void incrementFailure(String reason) {
        Counter.builder("global_single_writer_write_behind_failed")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    private void logDuplicateFailure(
            GlobalInMemoryWriteBehindEvent event,
            String reason,
            Object existsInDatabaseBeforeInsert,
            RuntimeException exception
    ) {
        log.warn(
                "Global write-behind duplicate insert. commandId={}, studentId={}, courseId={}, scenarioType={}, "
                        + "writerResult={}, alreadyExistsInMemoryBeforeSuccess={}, writeBehindAttemptNo={}, "
                        + "writeBehindWorkerName={}, existsInDatabaseBeforeInsert={}, reason={}",
                event.getCommandId(),
                event.getStudentId(),
                event.getCourseId(),
                event.getScenarioType(),
                event.getWriterResult(),
                event.isAlreadyExistsInMemoryBeforeSuccess(),
                event.getAttemptNo(),
                Thread.currentThread().getName(),
                existsInDatabaseBeforeInsert,
                reason,
                exception
        );
    }

    private Timer histogramTimer(String name) {
        return Timer.builder(name)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }
}
