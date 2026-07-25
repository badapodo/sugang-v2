package badapodo.sugang.service.globalinmemory;

import badapodo.sugang.service.inmemory.InMemoryEnrollmentResult;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.Getter;

@Getter
public class GlobalInMemoryEnrollmentCommand {

    private final UUID commandId;
    private final Long studentId;
    private final Long courseId;
    private final String scenarioType;
    private final Instant enqueuedAt;
    private final Instant requestedAt;
    private final CompletableFuture<GlobalInMemoryEnrollmentCommandResult> commandResultFuture;
    private final CompletableFuture<InMemoryEnrollmentResult> responseFuture;

    private GlobalInMemoryEnrollmentCommand(UUID commandId, Long studentId, Long courseId, String scenarioType) {
        this.commandId = commandId;
        this.studentId = studentId;
        this.courseId = courseId;
        this.scenarioType = scenarioType;
        this.requestedAt = Instant.now();
        this.enqueuedAt = requestedAt;
        this.commandResultFuture = new CompletableFuture<>();
        this.responseFuture = new CompletableFuture<>();
    }

    public static GlobalInMemoryEnrollmentCommand create(Long studentId, Long courseId, String scenarioType) {
        return new GlobalInMemoryEnrollmentCommand(UUID.randomUUID(), studentId, courseId, scenarioType);
    }

    public static GlobalInMemoryEnrollmentCommand create(
            UUID commandId,
            Long studentId,
            Long courseId,
            String scenarioType
    ) {
        return new GlobalInMemoryEnrollmentCommand(commandId, studentId, courseId, scenarioType);
    }

    public void complete(GlobalInMemoryEnrollmentCommandResult result) {
        commandResultFuture.complete(result);
        responseFuture.complete(toLegacyResult(result));
    }

    public void completeExceptionally(RuntimeException failure) {
        commandResultFuture.completeExceptionally(failure);
        responseFuture.complete(InMemoryEnrollmentResult.failure(failure));
    }

    private InMemoryEnrollmentResult toLegacyResult(GlobalInMemoryEnrollmentCommandResult result) {
        if (result.accepted()) {
            return InMemoryEnrollmentResult.success();
        }
        GlobalInMemoryEnrollmentRejected rejected = (GlobalInMemoryEnrollmentRejected) result;
        return InMemoryEnrollmentResult.failure(GlobalInMemoryRejectionExceptionMapper.toLegacyException(
                rejected.rejectionReason()
        ));
    }
}
