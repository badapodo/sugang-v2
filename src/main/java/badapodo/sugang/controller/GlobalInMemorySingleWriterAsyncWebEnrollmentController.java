package badapodo.sugang.controller;

import badapodo.sugang.controller.request.BaselineEnrollmentRequest;
import badapodo.sugang.controller.response.BaselineEnrollmentResponse;
import badapodo.sugang.exception.ApplicationException;
import badapodo.sugang.response.ErrorResponse;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryEnrollmentCommand;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryEnrollmentSubmission;
import badapodo.sugang.service.globalinmemory.GlobalInMemorySingleWriterEnrollmentService;
import badapodo.sugang.service.inmemory.InMemoryEnrollmentResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/api/global-in-memory-single-writer-async-web/enrollments")
public class GlobalInMemorySingleWriterAsyncWebEnrollmentController {

    private final GlobalInMemorySingleWriterEnrollmentService enrollmentService;
    private final long responseTimeoutMs;
    private final Counter enqueuedCounter;
    private final Counter timeoutCounter;
    private final Timer responseLatencyTimer;
    private final AtomicInteger inflightRequests = new AtomicInteger();

    public GlobalInMemorySingleWriterAsyncWebEnrollmentController(
            GlobalInMemorySingleWriterEnrollmentService enrollmentService,
            MeterRegistry meterRegistry,
            @Value("${GLOBAL_SINGLE_WRITER_RESPONSE_TIMEOUT_MS:5000}") long responseTimeoutMs
    ) {
        this.enrollmentService = enrollmentService;
        this.responseTimeoutMs = responseTimeoutMs;
        this.enqueuedCounter = Counter.builder("global_single_writer_async_web.enqueued.count")
                .register(meterRegistry);
        this.timeoutCounter = Counter.builder("global_single_writer_async_web.timeout.count")
                .register(meterRegistry);
        this.responseLatencyTimer = Timer.builder("global_single_writer_async_web.response.latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
        Gauge.builder(
                        "global_single_writer_async_web.inflight.count",
                        inflightRequests,
                        AtomicInteger::get
                )
                .register(meterRegistry);
    }

    @PostMapping
    public DeferredResult<ResponseEntity<?>> enroll(
            @Valid @RequestBody BaselineEnrollmentRequest request,
            @RequestHeader(value = "X-Scenario-Type", required = false) String scenarioType
    ) {
        Instant responseStartedAt = Instant.now();
        DeferredResult<ResponseEntity<?>> deferredResult = new DeferredResult<>(responseTimeoutMs);
        GlobalInMemoryEnrollmentSubmission submission = enrollmentService.submit(
                request.getStudentId(),
                request.getCourseId(),
                scenarioType
        );

        if (!submission.isAccepted()) {
            deferredResult.setResult(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(error("QueueFullException", "수강신청 요청이 일시적으로 많아 접수하지 못했습니다.")));
            return deferredResult;
        }

        enqueuedCounter.increment();
        inflightRequests.incrementAndGet();
        AtomicBoolean responseCompleted = new AtomicBoolean();
        AtomicBoolean completionRecorded = new AtomicBoolean();

        deferredResult.onTimeout(() -> {
            if (responseCompleted.compareAndSet(false, true)) {
                timeoutCounter.increment();
                deferredResult.setResult(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                        .body(error(
                                "GlobalInMemorySingleWriterAsyncWebResponseTimeoutException",
                                "수강신청 처리 응답 대기 시간이 초과되었습니다."
                        )));
            }
        });
        deferredResult.onCompletion(() -> {
            responseCompleted.set(true);
            if (completionRecorded.compareAndSet(false, true)) {
                inflightRequests.decrementAndGet();
                responseLatencyTimer.record(Duration.between(responseStartedAt, Instant.now()));
            }
        });

        GlobalInMemoryEnrollmentCommand command = submission.getCommand();
        command.getResponseFuture().whenComplete((result, throwable) -> {
            if (!responseCompleted.compareAndSet(false, true)) {
                return;
            }
            if (throwable != null) {
                deferredResult.setResult(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(error(
                                throwable.getClass().getSimpleName(),
                                "수강신청 처리 중 서버 오류가 발생했습니다."
                        )));
                return;
            }
            deferredResult.setResult(responseFromResult(result));
        });
        return deferredResult;
    }

    private ResponseEntity<?> responseFromResult(InMemoryEnrollmentResult result) {
        if (result.isSuccess()) {
            return ResponseEntity.ok(BaselineEnrollmentResponse.of("SUCCESS"));
        }
        RuntimeException failure = result.getFailure();
        if (failure instanceof ApplicationException applicationException) {
            return ResponseEntity.status(applicationException.getStatusCode())
                    .body(error(applicationException.getClass().getSimpleName(), applicationException.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(failure.getClass().getSimpleName(), "수강신청 처리 중 서버 오류가 발생했습니다."));
    }

    private ErrorResponse error(String reason, String message) {
        return ErrorResponse.builder()
                .status("FAIL")
                .reason(reason)
                .message(message)
                .build();
    }
}
