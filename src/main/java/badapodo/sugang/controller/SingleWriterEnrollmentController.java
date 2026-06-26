package badapodo.sugang.controller;

import badapodo.sugang.controller.request.BaselineEnrollmentRequest;
import badapodo.sugang.controller.response.SingleWriterEnrollmentAcceptedResponse;
import badapodo.sugang.response.ErrorResponse;
import badapodo.sugang.service.singlewriter.SingleWriterEnrollmentQueueService;
import badapodo.sugang.service.singlewriter.SingleWriterEnqueueResult;
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
@RequestMapping("/api/single-writer/enrollments")
@RequiredArgsConstructor
public class SingleWriterEnrollmentController {

    private final SingleWriterEnrollmentQueueService queueService;

    @PostMapping
    public ResponseEntity<?> enqueue(
            @Valid @RequestBody BaselineEnrollmentRequest request,
            @RequestHeader(value = "X-Scenario-Type", required = false) String scenarioType
    ) {
        SingleWriterEnqueueResult result = queueService.enqueue(
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

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(SingleWriterEnrollmentAcceptedResponse.of(
                        "ACCEPTED",
                        result.getCommandId(),
                        result.getPartitionIndex()
                ));
    }
}
