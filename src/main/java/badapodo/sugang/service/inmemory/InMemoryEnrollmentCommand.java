package badapodo.sugang.service.inmemory;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.Getter;

@Getter
public class InMemoryEnrollmentCommand {

    private final String commandId;
    private final Long studentId;
    private final Long courseId;
    private final String scenarioType;
    private final Instant enqueuedAt;
    private final CompletableFuture<InMemoryEnrollmentResult> responseFuture;

    private InMemoryEnrollmentCommand(Long studentId, Long courseId, String scenarioType) {
        this.commandId = UUID.randomUUID().toString();
        this.studentId = studentId;
        this.courseId = courseId;
        this.scenarioType = scenarioType;
        this.enqueuedAt = Instant.now();
        this.responseFuture = new CompletableFuture<>();
    }

    public static InMemoryEnrollmentCommand create(Long studentId, Long courseId, String scenarioType) {
        return new InMemoryEnrollmentCommand(studentId, courseId, scenarioType);
    }

    public void complete(InMemoryEnrollmentResult result) {
        responseFuture.complete(result);
    }
}
