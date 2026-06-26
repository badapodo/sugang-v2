package badapodo.sugang.service.singlewriter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(staticName = "of")
public class SingleWriterEnqueueResult {

    private final boolean accepted;
    private final String commandId;
    private final int partitionIndex;
}
