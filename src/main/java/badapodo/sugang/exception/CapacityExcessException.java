package badapodo.sugang.exception;

import static org.springframework.http.HttpStatus.CONFLICT;

public class CapacityExcessException extends ApplicationException{

    private static final String MESSAGE = "정원이 초과되었습니다.";

    public CapacityExcessException() {
        super(MESSAGE);
    }

    @Override
    public int getStatusCode() {
        return CONFLICT.value();
    }
}
