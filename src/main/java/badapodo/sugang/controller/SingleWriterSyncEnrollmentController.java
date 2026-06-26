package badapodo.sugang.controller;

import badapodo.sugang.controller.request.BaselineEnrollmentRequest;
import badapodo.sugang.controller.response.BaselineEnrollmentResponse;
import badapodo.sugang.exception.ApplicationException;
import badapodo.sugang.response.ErrorResponse;
import badapodo.sugang.service.singlewriter.SingleWriterEnrollmentQueueService;
import badapodo.sugang.service.singlewriter.SingleWriterProcessingResult;
import badapodo.sugang.service.singlewriter.SingleWriterSyncResult;
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
@RequestMapping("/api/single-writer-sync/enrollments")
@RequiredArgsConstructor
public class SingleWriterSyncEnrollmentController {

    private final SingleWriterEnrollmentQueueService queueService;

    @PostMapping
    public ResponseEntity<?> enroll(
            @Valid @RequestBody BaselineEnrollmentRequest request,
            @RequestHeader(value = "X-Scenario-Type", required = false) String scenarioType
    ) {
        SingleWriterSyncResult result = queueService.enqueueAndWait(
                request.getStudentId(),
                request.getCourseId(),
                scenarioType
        );

        if (!result.isAccepted()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ErrorResponse.builder()
                            .status("FAIL")
                            .reason("QueueFullException")
                            .message("수강신청 요청이 일시적으로 많아 접수하지 못했습니다.")
                            .build());
        }

        if (result.isTimedOut()) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ErrorResponse.builder()
                            .status("FAIL")
                            .reason("SingleWriterResponseTimeoutException")
                            .message("수강신청 처리 응답 대기 시간이 초과되었습니다.")
                            .build());
        }

        return responseFromProcessingResult(result.getProcessingResult());
    }

    private ResponseEntity<?> responseFromProcessingResult(SingleWriterProcessingResult processingResult) {
        if (processingResult.isSuccess()) {
            return ResponseEntity.ok(BaselineEnrollmentResponse.of("SUCCESS"));
        }

        RuntimeException failure = processingResult.getFailure();
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
