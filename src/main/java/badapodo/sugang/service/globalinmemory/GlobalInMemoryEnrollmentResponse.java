package badapodo.sugang.service.globalinmemory;

import badapodo.sugang.service.inmemory.InMemoryEnrollmentResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class GlobalInMemoryEnrollmentResponse {

    private final boolean accepted;
    private final boolean timedOut;
    private final String commandId;
    private final InMemoryEnrollmentResult enrollmentResult;

    public static GlobalInMemoryEnrollmentResponse queueFull(String commandId) {
        return new GlobalInMemoryEnrollmentResponse(false, false, commandId, null);
    }

    public static GlobalInMemoryEnrollmentResponse timeout(String commandId) {
        return new GlobalInMemoryEnrollmentResponse(true, true, commandId, null);
    }

    public static GlobalInMemoryEnrollmentResponse completed(
            String commandId,
            InMemoryEnrollmentResult enrollmentResult
    ) {
        return new GlobalInMemoryEnrollmentResponse(true, false, commandId, enrollmentResult);
    }
}
