package badapodo.sugang.exception;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

public class PrerequisiteNotMetException extends ApplicationException {

    private static final String MESSAGE = "선수 과목을 수강하지 않았습니다.";

    public PrerequisiteNotMetException() {
        super(MESSAGE);
    }

    @Override
    public int getStatusCode() {
        return BAD_REQUEST.value();
    }
}
