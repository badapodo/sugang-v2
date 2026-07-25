package badapodo.sugang.service.globalinmemory;

import java.util.UUID;

public sealed interface GlobalInMemoryEnrollmentCommandResult
        permits GlobalInMemoryEnrollmentAccepted, GlobalInMemoryEnrollmentRejected {

    UUID commandId();

    Long studentId();

    Long courseId();

    String message();

    boolean accepted();
}
