package badapodo.sugang.service.inmemory;

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
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class InMemoryEnrollmentWriteBehindService {

    private final EnrollmentRepository enrollmentRepository;
    private final BlockingQueue<InMemoryEnrollmentWriteBehindEvent> writeBehindQueue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Counter writeBehindEnqueuedCounter;
    private final Counter writeBehindSuccessCounter;
    private final Counter writeBehindFailedCounter;
    private ExecutorService executorService;

    public InMemoryEnrollmentWriteBehindService(
            EnrollmentRepository enrollmentRepository,
            MeterRegistry meterRegistry,
            @Value("${IN_MEMORY_SINGLE_WRITER_WRITE_BEHIND_QUEUE_CAPACITY:100000}") int writeBehindQueueCapacity
    ) {
        if (writeBehindQueueCapacity <= 0) {
            throw new IllegalArgumentException("IN_MEMORY_SINGLE_WRITER_WRITE_BEHIND_QUEUE_CAPACITY must be greater than 0");
        }

        this.enrollmentRepository = enrollmentRepository;
        this.writeBehindQueue = new ArrayBlockingQueue<>(writeBehindQueueCapacity);
        this.writeBehindEnqueuedCounter = Counter.builder("in_memory_single_writer.write_behind.enqueued.count")
                .register(meterRegistry);
        this.writeBehindSuccessCounter = Counter.builder("in_memory_single_writer.write_behind.success.count")
                .register(meterRegistry);
        this.writeBehindFailedCounter = Counter.builder("in_memory_single_writer.write_behind.failed.count")
                .register(meterRegistry);

        Gauge.builder("in_memory_single_writer.write_behind.queue.depth", writeBehindQueue, BlockingQueue::size)
                .register(meterRegistry);
    }

    @PostConstruct
    public void startWriter() {
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(this::consumeWriteBehindQueue);
        log.info("Started in-memory single-writer write-behind worker.");
    }

    @PreDestroy
    public void stopWriter() {
        running.set(false);
        if (executorService == null) {
            return;
        }

        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("In-memory single-writer write-behind worker did not terminate within timeout.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void enqueue(InMemoryEnrollmentWriteBehindEvent event) {
        try {
            writeBehindQueue.put(event);
            writeBehindEnqueuedCounter.increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("write-behind queue enqueue was interrupted.", e);
        }
    }

    private void consumeWriteBehindQueue() {
        while (running.get() || !writeBehindQueue.isEmpty()) {
            try {
                InMemoryEnrollmentWriteBehindEvent event = writeBehindQueue.poll(500, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }

                saveEnrollment(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Transactional
    protected void saveEnrollment(InMemoryEnrollmentWriteBehindEvent event) {
        try {
            enrollmentRepository.saveAndFlush(Enrollment.create(event.getStudentId(), event.getCourseId()));
            writeBehindSuccessCounter.increment();
        } catch (RuntimeException e) {
            writeBehindFailedCounter.increment();
            log.warn(
                    "In-memory write-behind insert failed. commandId={}, studentId={}, courseId={}, reason={}",
                    event.getCommandId(),
                    event.getStudentId(),
                    event.getCourseId(),
                    e.getClass().getSimpleName(),
                    e
            );
        }
    }
}
