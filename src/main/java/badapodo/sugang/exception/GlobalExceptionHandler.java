package badapodo.sugang.exception;

import badapodo.sugang.response.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    public ErrorResponse applicationException(HttpServletResponse response, ApplicationException e) {

        response.setStatus(e.getStatusCode());

        return ErrorResponse.builder()
                .status("FAIL")
                .reason(e.getClass().getSimpleName())
                .message(e.getMessage())
                .build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ErrorResponse validationException(HttpServletResponse response, MethodArgumentNotValidException e) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());

        return ErrorResponse.builder()
                .status("FAIL")
                .reason("INVALID_REQUEST")
                .message("요청 값이 올바르지 않습니다.")
                .build();
    }
}
