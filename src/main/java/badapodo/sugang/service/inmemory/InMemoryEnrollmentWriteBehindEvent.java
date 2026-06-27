package badapodo.sugang.service.inmemory;

import java.time.Instant;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(staticName = "of")
public class InMemoryEnrollmentWriteBehindEvent {

    private final String commandId;
    private final Long studentId;
    private final Long courseId;
    private final Instant matchedAt;
}
