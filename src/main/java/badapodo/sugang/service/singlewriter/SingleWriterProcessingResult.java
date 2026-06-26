package badapodo.sugang.service.singlewriter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(staticName = "of")
public class SingleWriterProcessingResult {

    private final boolean success;
    private final RuntimeException failure;

    public static SingleWriterProcessingResult success() {
        return new SingleWriterProcessingResult(true, null);
    }

    public static SingleWriterProcessingResult failure(RuntimeException failure) {
        return new SingleWriterProcessingResult(false, failure);
    }
}
