package badapodo.sugang.controller.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(staticName = "of")
public class SingleWriterEnrollmentAcceptedResponse {

    private final String status;
    private final String commandId;
    private final int partitionIndex;
}
