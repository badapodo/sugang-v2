package badapodo.sugang.service.inmemory;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class InMemoryEnrollmentResult {

    private final boolean success;
    private final RuntimeException failure;

    public static InMemoryEnrollmentResult success() {
        return new InMemoryEnrollmentResult(true, null);
    }

    public static InMemoryEnrollmentResult failure(RuntimeException failure) {
        return new InMemoryEnrollmentResult(false, failure);
    }
}
