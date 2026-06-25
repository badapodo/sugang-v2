package badapodo.sugang.exception;

import static org.springframework.http.HttpStatus.CONFLICT;

public class DuplicateEnrollmentException extends ApplicationException {

    private static final String MESSAGE = "현재 학기에 이미 신청한 과목입니다.";

    public DuplicateEnrollmentException() {
        super(MESSAGE);
    }

    @Override
    public int getStatusCode() {
        return CONFLICT.value();
    }
}
