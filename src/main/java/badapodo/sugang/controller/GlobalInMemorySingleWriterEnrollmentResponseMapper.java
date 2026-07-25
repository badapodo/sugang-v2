package badapodo.sugang.controller;

import badapodo.sugang.controller.response.GlobalInMemorySingleWriterEnrollmentResponse;
import badapodo.sugang.service.globalinmemory.CommandTimeoutException;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryCommandResponse;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryEnrollmentAccepted;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryEnrollmentCommandResult;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryEnrollmentRejected;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryEnrollmentRejectionReason;
import badapodo.sugang.service.globalinmemory.GlobalSingleWriterSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class GlobalInMemorySingleWriterEnrollmentResponseMapper {

    private GlobalInMemorySingleWriterEnrollmentResponseMapper() {
    }

    static ResponseEntity<GlobalInMemorySingleWriterEnrollmentResponse> invalid(
            String reason,
            String message
    ) {
        return ResponseEntity.badRequest()
                .body(new GlobalInMemorySingleWriterEnrollmentResponse(
                        null,
                        "INVALID_REQUEST",
                        reason,
                        null,
                        null,
                        message
                ));
    }

    static ResponseEntity<GlobalInMemorySingleWriterEnrollmentResponse> from(GlobalInMemoryCommandResponse response) {
        if (!response.isAcceptedByWriter()) {
            String reason = response.isCommandIdConflict() ? "COMMAND_ID_CONFLICT" : "WRITER_UNAVAILABLE";
            HttpStatus status = response.isCommandIdConflict() ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE;
            return ResponseEntity.status(status)
                    .body(new GlobalInMemorySingleWriterEnrollmentResponse(
                            response.getCommandId().toString(),
                            "FAILED",
                            reason,
                            null,
                            null,
                            response.getMessage()
                    ));
        }
        if (response.isTimedOut()) {
            return failed(
                    response.getCommandId().toString(),
                    "COMMAND_TIMEOUT",
                    "요청 처리 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        return fromResult(response.getResult());
    }

    static ResponseEntity<GlobalInMemorySingleWriterEnrollmentResponse> failed(
            String commandId,
            GlobalSingleWriterSystemException exception
    ) {
        boolean timedOut = exception instanceof CommandTimeoutException;
        HttpStatus status = timedOut ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        return failed(
                commandId,
                exception.getReason().name(),
                timedOut
                        ? "요청 처리 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."
                        : "요청을 처리하는 중 내부 오류가 발생했습니다.",
                status
        );
    }

    private static ResponseEntity<GlobalInMemorySingleWriterEnrollmentResponse> fromResult(
            GlobalInMemoryEnrollmentCommandResult result
    ) {
        if (result instanceof GlobalInMemoryEnrollmentAccepted accepted) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new GlobalInMemorySingleWriterEnrollmentResponse(
                            accepted.commandId().toString(),
                            "ACCEPTED",
                            null,
                            accepted.studentId(),
                            accepted.courseId(),
                            accepted.message()
                    ));
        }

        GlobalInMemoryEnrollmentRejected rejected = (GlobalInMemoryEnrollmentRejected) result;
        return ResponseEntity.status(statusFor(rejected.rejectionReason()))
                .body(new GlobalInMemorySingleWriterEnrollmentResponse(
                        rejected.commandId().toString(),
                        "REJECTED",
                        rejected.rejectionReason().name(),
                        rejected.studentId(),
                        rejected.courseId(),
                        rejected.message()
                ));
    }

    private static ResponseEntity<GlobalInMemorySingleWriterEnrollmentResponse> failed(
            String commandId,
            String reason,
            String message,
            HttpStatus status
    ) {
        return ResponseEntity.status(status)
                .body(new GlobalInMemorySingleWriterEnrollmentResponse(
                        commandId,
                        "FAILED",
                        reason,
                        null,
                        null,
                        message
                ));
    }

    private static HttpStatus statusFor(GlobalInMemoryEnrollmentRejectionReason reason) {
        return switch (reason) {
            case STUDENT_NOT_FOUND, COURSE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CAPACITY_EXCEEDED, DUPLICATE_ENROLLMENT, SCHEDULE_CONFLICT -> HttpStatus.CONFLICT;
            case PREREQUISITE_NOT_MET -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
