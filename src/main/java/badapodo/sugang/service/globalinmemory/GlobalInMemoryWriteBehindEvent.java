package badapodo.sugang.service.globalinmemory;

import java.time.Instant;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(staticName = "of")
public class GlobalInMemoryWriteBehindEvent {

    private final String commandId;
    private final Long studentId;
    private final Long courseId;
    private final Instant matchedAt;
}
