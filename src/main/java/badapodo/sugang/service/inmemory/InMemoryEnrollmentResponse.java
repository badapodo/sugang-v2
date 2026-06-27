package badapodo.sugang.service.inmemory;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class InMemoryEnrollmentResponse {

    private final boolean accepted;
    private final boolean timedOut;
    private final String commandId;
    private final int partitionIndex;
    private final InMemoryEnrollmentResult enrollmentResult;

    public static InMemoryEnrollmentResponse queueFull(String commandId, int partitionIndex) {
        return new InMemoryEnrollmentResponse(false, false, commandId, partitionIndex, null);
    }

    public static InMemoryEnrollmentResponse timeout(String commandId, int partitionIndex) {
        return new InMemoryEnrollmentResponse(true, true, commandId, partitionIndex, null);
    }

    public static InMemoryEnrollmentResponse completed(
            String commandId,
            int partitionIndex,
            InMemoryEnrollmentResult enrollmentResult
    ) {
        return new InMemoryEnrollmentResponse(true, false, commandId, partitionIndex, enrollmentResult);
    }
}
