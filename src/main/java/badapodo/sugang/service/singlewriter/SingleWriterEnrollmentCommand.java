package badapodo.sugang.service.singlewriter;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class SingleWriterEnrollmentCommand {

    private final String commandId;
    private final Long studentId;
    private final Long courseId;
    private final String scenarioType;
    private final Instant enqueuedAt;
    private final CompletableFuture<SingleWriterProcessingResult> responseFuture;

    public static SingleWriterEnrollmentCommand create(Long studentId, Long courseId, String scenarioType) {
        return new SingleWriterEnrollmentCommand(
                UUID.randomUUID().toString(),
                studentId,
                courseId,
                scenarioType,
                Instant.now(),
                null
        );
    }

    public static SingleWriterEnrollmentCommand createSync(Long studentId, Long courseId, String scenarioType) {
        return new SingleWriterEnrollmentCommand(
                UUID.randomUUID().toString(),
                studentId,
                courseId,
                scenarioType,
                Instant.now(),
                new CompletableFuture<>()
        );
    }

    public Optional<CompletableFuture<SingleWriterProcessingResult>> responseFuture() {
        return Optional.ofNullable(responseFuture);
    }

    public void complete(SingleWriterProcessingResult result) {
        responseFuture().ifPresent(future -> future.complete(result));
    }
}
