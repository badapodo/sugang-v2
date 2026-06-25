package badapodo.sugang.exception;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;

@Getter
public abstract class ApplicationException extends RuntimeException{

    private final Map<String, String> validation = new HashMap<>();

    protected ApplicationException(String message) {
        super(message);
    }

    protected ApplicationException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract int getStatusCode();

    public void addValidation(String filedName, String message) {
        this.validation.put(filedName, message);
    }

}
