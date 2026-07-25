package badapodo.sugang.service.globalinmemory;

public class CommandProcessingException extends GlobalSingleWriterSystemException {

    public CommandProcessingException(String message, Throwable cause) {
        super(GlobalSingleWriterSystemFailureReason.COMMAND_PROCESSING_FAILED, message, cause);
    }
}
