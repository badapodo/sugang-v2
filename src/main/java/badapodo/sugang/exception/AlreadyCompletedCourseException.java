package badapodo.sugang.exception;

import static org.springframework.http.HttpStatus.CONFLICT;

public class AlreadyCompletedCourseException extends ApplicationException {

    private static final String MESSAGE = "과거에 이미 수강 완료한 과목입니다.";

    public AlreadyCompletedCourseException() {
        super(MESSAGE);
    }

    @Override
    public int getStatusCode() {
        return CONFLICT.value();
    }
}
