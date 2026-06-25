package badapodo.sugang.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ErrorResponse {

    private final String status;
    private final String reason;
    private final String message;
}
