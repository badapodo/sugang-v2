package badapodo.sugang.service.globalinmemory;

import java.util.UUID;

public record GlobalInMemoryEnrollmentRejected(
        UUID commandId,
        Long studentId,
        Long courseId,
        GlobalInMemoryEnrollmentRejectionReason rejectionReason,
        String message
) implements GlobalInMemoryEnrollmentCommandResult {

    public static GlobalInMemoryEnrollmentRejected of(
            UUID commandId,
            Long studentId,
            Long courseId,
            GlobalInMemoryEnrollmentRejectionReason rejectionReason
    ) {
        return new GlobalInMemoryEnrollmentRejected(
                commandId,
                studentId,
                courseId,
                rejectionReason,
                rejectionReason.getMessage()
        );
    }

    @Override
    public boolean accepted() {
        return false;
    }
}
