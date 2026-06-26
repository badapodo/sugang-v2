package badapodo.sugang.service.singlewriter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(staticName = "of")
public class SingleWriterSyncResult {

    private final boolean accepted;
    private final boolean timedOut;
    private final String commandId;
    private final int partitionIndex;
    private final SingleWriterProcessingResult processingResult;

    public static SingleWriterSyncResult queueFull(String commandId, int partitionIndex) {
        return new SingleWriterSyncResult(false, false, commandId, partitionIndex, null);
    }

    public static SingleWriterSyncResult timeout(String commandId, int partitionIndex) {
        return new SingleWriterSyncResult(true, true, commandId, partitionIndex, null);
    }

    public static SingleWriterSyncResult completed(
            String commandId,
            int partitionIndex,
            SingleWriterProcessingResult processingResult
    ) {
        return new SingleWriterSyncResult(true, false, commandId, partitionIndex, processingResult);
    }
}
