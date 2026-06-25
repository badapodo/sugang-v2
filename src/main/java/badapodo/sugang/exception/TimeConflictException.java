package badapodo.sugang.exception;

import static org.springframework.http.HttpStatus.CONFLICT;

public class TimeConflictException extends ApplicationException {

    private static final String MESSAGE = "시간표가 중복되는 강의가 있습니다.";

    public TimeConflictException() {
        super(MESSAGE);
    }

    @Override
    public int getStatusCode() {
        return CONFLICT.value();
    }
}
