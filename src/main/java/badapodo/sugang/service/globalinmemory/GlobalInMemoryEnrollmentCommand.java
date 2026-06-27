package badapodo.sugang.service.globalinmemory;

import badapodo.sugang.service.inmemory.InMemoryEnrollmentResult;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.Getter;

@Getter
public class GlobalInMemoryEnrollmentCommand {

    private final String commandId;
    private final Long studentId;
    private final Long courseId;
    private final String scenarioType;
    private final Instant enqueuedAt;
    private final CompletableFuture<InMemoryEnrollmentResult> responseFuture;

    private GlobalInMemoryEnrollmentCommand(Long studentId, Long courseId, String scenarioType) {
        this.commandId = UUID.randomUUID().toString();
        this.studentId = studentId;
        this.courseId = courseId;
        this.scenarioType = scenarioType;
        this.enqueuedAt = Instant.now();
        this.responseFuture = new CompletableFuture<>();
    }

    public static GlobalInMemoryEnrollmentCommand create(Long studentId, Long courseId, String scenarioType) {
        return new GlobalInMemoryEnrollmentCommand(studentId, courseId, scenarioType);
    }

    public void complete(InMemoryEnrollmentResult result) {
        responseFuture.complete(result);
    }
}
