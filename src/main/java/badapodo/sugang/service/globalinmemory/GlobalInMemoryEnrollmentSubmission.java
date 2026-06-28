package badapodo.sugang.service.globalinmemory;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class GlobalInMemoryEnrollmentSubmission {

    private final boolean accepted;
    private final GlobalInMemoryEnrollmentCommand command;

    public static GlobalInMemoryEnrollmentSubmission accepted(GlobalInMemoryEnrollmentCommand command) {
        return new GlobalInMemoryEnrollmentSubmission(true, command);
    }

    public static GlobalInMemoryEnrollmentSubmission rejected(GlobalInMemoryEnrollmentCommand command) {
        return new GlobalInMemoryEnrollmentSubmission(false, command);
    }
}
