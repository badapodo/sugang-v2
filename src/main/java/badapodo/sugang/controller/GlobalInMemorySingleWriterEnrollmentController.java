package badapodo.sugang.controller;

import badapodo.sugang.controller.request.BaselineEnrollmentRequest;
import badapodo.sugang.controller.response.BaselineEnrollmentResponse;
import badapodo.sugang.exception.ApplicationException;
import badapodo.sugang.response.ErrorResponse;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryEnrollmentResponse;
import badapodo.sugang.service.globalinmemory.GlobalInMemorySingleWriterEnrollmentService;
import badapodo.sugang.service.inmemory.InMemoryEnrollmentResult;
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
@RequestMapping("/api/global-in-memory-single-writer/enrollments")
@RequiredArgsConstructor
public class GlobalInMemorySingleWriterEnrollmentController {

    private final GlobalInMemorySingleWriterEnrollmentService enrollmentService;

    @PostMapping
    public ResponseEntity<?> enroll(
            @Valid @RequestBody BaselineEnrollmentRequest request,
            @RequestHeader(value = "X-Scenario-Type", required = false) String scenarioType
    ) {
        GlobalInMemoryEnrollmentResponse response = enrollmentService.enroll(
                request.getStudentId(),
                request.getCourseId(),
                scenarioType
        );
        if (!response.isAccepted()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(error("QueueFullException", "수강신청 요청이 일시적으로 많아 접수하지 못했습니다."));
        }
        if (response.isTimedOut()) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(error(
                            "GlobalInMemorySingleWriterResponseTimeoutException",
                            "수강신청 처리 응답 대기 시간이 초과되었습니다."
                    ));
        }
        return responseFromResult(response.getEnrollmentResult());
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
