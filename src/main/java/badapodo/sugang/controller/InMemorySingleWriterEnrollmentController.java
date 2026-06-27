package badapodo.sugang.controller;

import badapodo.sugang.controller.request.BaselineEnrollmentRequest;
import badapodo.sugang.controller.response.BaselineEnrollmentResponse;
import badapodo.sugang.exception.ApplicationException;
import badapodo.sugang.response.ErrorResponse;
import badapodo.sugang.service.inmemory.InMemoryEnrollmentResponse;
import badapodo.sugang.service.inmemory.InMemoryEnrollmentResult;
import badapodo.sugang.service.inmemory.InMemorySingleWriterEnrollmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/in-memory-single-writer/enrollments")
@RequiredArgsConstructor
public class InMemorySingleWriterEnrollmentController {

    private final InMemorySingleWriterEnrollmentService enrollmentService;

    @PostMapping
    public ResponseEntity<?> enroll(
            @Valid @RequestBody BaselineEnrollmentRequest request,
            @RequestHeader(value = "X-Scenario-Type", required = false) String scenarioType
    ) {
        InMemoryEnrollmentResponse response = enrollmentService.enroll(
                request.getStudentId(),
                request.getCourseId(),
                scenarioType
        );

        if (!response.isAccepted()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ErrorResponse.builder()
                            .status("FAIL")
                            .reason("QueueFullException")
                            .message("수강신청 요청이 일시적으로 많아 접수하지 못했습니다.")
                            .build());
        }

        if (response.isTimedOut()) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ErrorResponse.builder()
                            .status("FAIL")
                            .reason("InMemorySingleWriterResponseTimeoutException")
                            .message("수강신청 처리 응답 대기 시간이 초과되었습니다.")
                            .build());
        }

        return responseFromEnrollmentResult(response.getEnrollmentResult());
    }

    private ResponseEntity<?> responseFromEnrollmentResult(InMemoryEnrollmentResult enrollmentResult) {
        if (enrollmentResult.isSuccess()) {
            return ResponseEntity.ok(BaselineEnrollmentResponse.of("SUCCESS"));
        }

        RuntimeException failure = enrollmentResult.getFailure();
        if (failure instanceof ApplicationException applicationException) {
            return ResponseEntity.status(applicationException.getStatusCode())
                    .body(ErrorResponse.builder()
                            .status("FAIL")
                            .reason(applicationException.getClass().getSimpleName())
                            .message(applicationException.getMessage())
                            .build());
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.builder()
                        .status("FAIL")
                        .reason(failure.getClass().getSimpleName())
                        .message("수강신청 처리 중 서버 오류가 발생했습니다.")
                        .build());
    }
}
