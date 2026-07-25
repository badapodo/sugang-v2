package badapodo.sugang.service.globalinmemory;

public class CommandTimeoutException extends GlobalSingleWriterSystemException {

    public CommandTimeoutException(String message) {
        super(GlobalSingleWriterSystemFailureReason.COMMAND_TIMEOUT, message);
    }
}
