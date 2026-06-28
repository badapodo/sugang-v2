package badapodo.sugang.service.globalinmemory;

import badapodo.sugang.domain.Enrollment;
import badapodo.sugang.repository.EnrollmentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class GlobalInMemoryWriteBehindService {

    private static final String MODE_SINGLE_JPA = "single-jpa";
    private static final String MODE_BATCH_JDBC = "batch-jdbc";
    private static final String BATCH_INSERT_SQL = """
            INSERT INTO enrollment (
                student_id,
                course_id,
                created_date,
                last_modified_date
            )
            VALUES (?, ?, ?, ?)
            ON CONFLICT (student_id, course_id) DO NOTHING
            """;

    private final EnrollmentRepository enrollmentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int workerCount;
    private final String persistenceMode;
    private final int batchSize;
    private final long batchWaitMs;
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
    private final DistributionSummary batchSizeSummary;
    private final Counter batchSuccessCounter;
    private final Counter batchFailedCounter;
    private final Timer batchLatencyTimer;
    private final Set<String> enqueuedCommandIds = ConcurrentHashMap.newKeySet();
    private final Set<String> processedCommandIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger workerSequence = new AtomicInteger();
    private ExecutorService executorService;

    public GlobalInMemoryWriteBehindService(
            EnrollmentRepository enrollmentRepository,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry,
            @Value("${GLOBAL_SINGLE_WRITER_QUEUE_CAPACITY:100000}") int queueCapacity,
            @Value("${GLOBAL_SINGLE_WRITER_WRITE_BEHIND_WORKER_COUNT:4}") int workerCount,
            @Value("${WRITE_BEHIND_MODE:batch-jdbc}") String persistenceMode,
            @Value("${WRITE_BEHIND_BATCH_SIZE:500}") int batchSize,
            @Value("${WRITE_BEHIND_BATCH_WAIT_MS:10}") long batchWaitMs
    ) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("GLOBAL_SINGLE_WRITER_QUEUE_CAPACITY must be greater than 0");
        }
        if (workerCount <= 0) {
            throw new IllegalArgumentException("GLOBAL_SINGLE_WRITER_WRITE_BEHIND_WORKER_COUNT must be greater than 0");
        }
        if (!Set.of(MODE_SINGLE_JPA, MODE_BATCH_JDBC).contains(persistenceMode)) {
            throw new IllegalArgumentException(
                    "WRITE_BEHIND_MODE must be one of: " + MODE_SINGLE_JPA + ", " + MODE_BATCH_JDBC
            );
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("WRITE_BEHIND_BATCH_SIZE must be greater than 0");
        }
        if (batchWaitMs < 0) {
            throw new IllegalArgumentException("WRITE_BEHIND_BATCH_WAIT_MS must be 0 or greater");
        }

        this.enrollmentRepository = enrollmentRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.workerCount = workerCount;
        this.persistenceMode = persistenceMode;
        this.batchSize = batchSize;
        this.batchWaitMs = batchWaitMs;
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
        this.batchSizeSummary = DistributionSummary.builder("write_behind.batch.size")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.batchSuccessCounter = Counter.builder("write_behind.batch.success.count")
                .register(meterRegistry);
        this.batchFailedCounter = Counter.builder("write_behind.batch.failed.count")
                .register(meterRegistry);
        this.batchLatencyTimer = histogramTimer("write_behind.batch.latency");
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
        log.info(
                "Started global in-memory write-behind workers. workerCount={}, mode={}, batchSize={}, batchWaitMs={}",
                workerCount,
                persistenceMode,
                batchSize,
                batchWaitMs
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
        if (MODE_BATCH_JDBC.equals(persistenceMode)) {
            consumeBatches();
            return;
        }
        consumeSingleEvents();
    }

    private void consumeSingleEvents() {
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

    private void consumeBatches() {
        while (running.get() || !queue.isEmpty()) {
            try {
                GlobalInMemoryWriteBehindEvent firstEvent = queue.poll(500, TimeUnit.MILLISECONDS);
                if (firstEvent == null) {
                    continue;
                }
                List<GlobalInMemoryWriteBehindEvent> batch = collectBatch(firstEvent);
                List<GlobalInMemoryWriteBehindEvent> processableBatch = new ArrayList<>(batch.size());
                for (GlobalInMemoryWriteBehindEvent event : batch) {
                    processedCounter.increment();
                    if (!processedCommandIds.add(event.getCommandId())) {
                        duplicateCommandProcessedCounter.increment();
                        incrementFailure("DuplicateCommandProcessed");
                        logDuplicateFailure(event, "DuplicateCommandProcessed", "NOT_CHECKED", null);
                        continue;
                    }
                    processableBatch.add(event);
                }
                if (!processableBatch.isEmpty()) {
                    persistBatch(processableBatch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<GlobalInMemoryWriteBehindEvent> collectBatch(GlobalInMemoryWriteBehindEvent firstEvent)
            throws InterruptedException {
        List<GlobalInMemoryWriteBehindEvent> batch = new ArrayList<>(batchSize);
        batch.add(firstEvent);
        queue.drainTo(batch, batchSize - 1);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(batchWaitMs);

        while (batch.size() < batchSize) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            GlobalInMemoryWriteBehindEvent event = queue.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (event == null) {
                break;
            }
            batch.add(event);
            queue.drainTo(batch, batchSize - batch.size());
        }
        return batch;
    }

    private void persistBatch(List<GlobalInMemoryWriteBehindEvent> batch) {
        Instant startedAt = Instant.now();
        batchSizeSummary.record(batch.size());
        for (GlobalInMemoryWriteBehindEvent event : batch) {
            lagTimer.record(Duration.between(event.getWriteBehindEnqueuedAt(), startedAt));
        }

        try {
            Timestamp persistedAt = Timestamp.from(Instant.now());
            int[] updateCounts = transactionTemplate.execute(status -> jdbcTemplate.batchUpdate(
                    BATCH_INSERT_SQL,
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement statement, int index) throws SQLException {
                            GlobalInMemoryWriteBehindEvent event = batch.get(index);
                            statement.setLong(1, event.getStudentId());
                            statement.setLong(2, event.getCourseId());
                            statement.setTimestamp(3, persistedAt);
                            statement.setTimestamp(4, persistedAt);
                        }

                        @Override
                        public int getBatchSize() {
                            return batch.size();
                        }
                    }
            ));
            recordBatchResults(batch, updateCounts);
            batchSuccessCounter.increment();
        } catch (RuntimeException e) {
            batchFailedCounter.increment();
            for (GlobalInMemoryWriteBehindEvent event : batch) {
                incrementFailure("Batch" + e.getClass().getSimpleName());
            }
            log.warn(
                    "Global write-behind batch insert failed. batchSize={}, writeBehindWorkerName={}, reason={}",
                    batch.size(),
                    Thread.currentThread().getName(),
                    e.getClass().getSimpleName(),
                    e
            );
        } finally {
            Duration elapsed = Duration.between(startedAt, Instant.now());
            batchLatencyTimer.record(elapsed);
            latencyTimer.record(elapsed);
        }
    }

    private void recordBatchResults(List<GlobalInMemoryWriteBehindEvent> batch, int[] updateCounts) {
        if (updateCounts == null || updateCounts.length != batch.size()) {
            throw new IllegalStateException("Unexpected JDBC batch update count.");
        }
        for (int index = 0; index < updateCounts.length; index++) {
            GlobalInMemoryWriteBehindEvent event = batch.get(index);
            if (updateCounts[index] == 0) {
                duplicateCounter.increment();
                incrementFailure("AlreadyExistsBeforeInsert");
                logDuplicateFailure(event, "AlreadyExistsBeforeInsert", true, null);
            } else {
                successCounter.increment();
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
