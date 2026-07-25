package badapodo.sugang.service.globalinmemory;

import java.util.UUID;

public record GlobalInMemoryEnrollmentAccepted(
        UUID commandId,
        Long studentId,
        Long courseId,
        String message
) implements GlobalInMemoryEnrollmentCommandResult {

    public static GlobalInMemoryEnrollmentAccepted of(UUID commandId, Long studentId, Long courseId) {
        return new GlobalInMemoryEnrollmentAccepted(
                commandId,
                studentId,
                courseId,
                "수강신청이 완료되었습니다."
        );
    }

    @Override
    public boolean accepted() {
        return true;
    }
}
