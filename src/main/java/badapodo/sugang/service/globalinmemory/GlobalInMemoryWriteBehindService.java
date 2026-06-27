package badapodo.sugang.service.globalinmemory;

import badapodo.sugang.domain.Enrollment;
import badapodo.sugang.repository.EnrollmentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final Counter successCounter;
    private final Counter failedCounter;
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
        this.enqueuedCounter = Counter.builder("global_single_writer.write_behind.enqueued.count")
                .register(meterRegistry);
        this.successCounter = Counter.builder("global_single_writer.write_behind.success.count")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("global_single_writer.write_behind.failed.count")
                .register(meterRegistry);
        Gauge.builder("global_single_writer.write_behind.queue.depth", queue, BlockingQueue::size)
                .register(meterRegistry);
    }

    @PostConstruct
    public void startWorkers() {
        executorService = Executors.newFixedThreadPool(workerCount);
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
                    persist(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void persist(GlobalInMemoryWriteBehindEvent event) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    enrollmentRepository.saveAndFlush(Enrollment.create(event.getStudentId(), event.getCourseId()))
            );
            successCounter.increment();
        } catch (RuntimeException e) {
            failedCounter.increment();
            log.warn(
                    "Global in-memory write-behind insert failed. commandId={}, studentId={}, courseId={}, reason={}",
                    event.getCommandId(),
                    event.getStudentId(),
                    event.getCourseId(),
                    e.getClass().getSimpleName(),
                    e
            );
        }
    }
}
