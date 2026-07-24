package badapodo.sugang.controller;

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

    
//    @PostMapping
//    public ResponseEntity<?> enroll(
//            @Valid @RequestBody BaselineEnrollmentRequest request,
//            @RequestHeader(value = "X-Scenario-Type", required = false) String scenarioType
//    ) {
////        GlobalInMemoryEnrollmentResponse response = enrollmentService.enroll(
////                request.getStudentId(),
////                request.getCourseId(),
////                scenarioType
////        );
//        GlobalInMemoryEnrollmentResponse response = enrollmentService.enroll(
//                request.studentId(),
//                request.courseId(),
//                scenarioType
//        );
//        if (!response.isAccepted()) {
//            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
//                    .body(error("QueueFullException", "수강신청 요청이 일시적으로 많아 접수하지 못했습니다."));
//        }
//        if (response.isTimedOut()) {
//            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
//                    .body(error(
//                            "GlobalInMemorySingleWriterResponseTimeoutException",
//                            "수강신청 처리 응답 대기 시간이 초과되었습니다."
//                    ));
//        }
//        return responseFromResult(response.getEnrollmentResult());
//    }

    @PostMapping
    public ResponseEntity<?> enroll(
            @RequestBody byte[] payload, // InputStream/String 변환 없이 byte[]로 바로 직수신
            @RequestHeader(value = "X-Scenario-Type", required = false) String scenarioType
    ) {
        // 1. Zero-Allocation Fast Byte Parsing (Jackson, Reflection 완전히 우회)
        long[] ids = parseTwoLongsFromFastJson(payload);
        long studentId = ids[0];
        long courseId = ids[1];

        // 2. 간단한 수동 Validation (@Valid 오버헤드 제거)
        if (studentId <= 0 || courseId <= 0) {
            return ResponseEntity.badRequest()
                    .body(error("IllegalArgumentException", "유효하지 않은 요청 데이터입니다."));
        }

        // 3. 기존 서비스 비즈니스 로직 호출 (기존 코드 그대로 유지)
        GlobalInMemoryEnrollmentResponse response = enrollmentService.enroll(
                studentId,
                courseId,
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

    /**
     * JSON Body (byte[]) 상에서 ASCII 숫자를 직접 스캐닝하여 Long 2개를 추출
     * String 객체 생성 0건, Reflection 0건 (Zero-Allocation)
     */
    private long[] parseTwoLongsFromFastJson(byte[] payload) {
        long id1 = 0L;
        long id2 = 0L;
        long currentVal = 0L;
        boolean hasValue = false;

        for (int i = 0; i < payload.length; i++) {
            byte b = payload[i];

            // ASCII 코드 '0'(0x30) ~ '9'(0x39) 체크
            if (b >= '0' && b <= '9') {
                currentVal = currentVal * 10 + (b - '0');
                hasValue = true;
            } else if (hasValue) {
                if (id1 == 0L) {
                    id1 = currentVal;
                } else {
                    id2 = currentVal;
                    break; // Long 2개를 모두 찾았으므로 즉시 루프 종료
                }
                currentVal = 0L;
                hasValue = false;
            }
        }

        // JSON 맨 끝에 숫자가 바로 붙어 끝나는 케이스 처리
        if (hasValue && id2 == 0L) {
            if (id1 == 0L) id1 = currentVal;
            else id2 = currentVal;
        }

        return new long[]{id1, id2};
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
