package badapodo.sugang.service.globalinmemory;

public class GlobalSingleWriterInternalStateException extends GlobalSingleWriterSystemException {

    public GlobalSingleWriterInternalStateException(String message) {
        super(GlobalSingleWriterSystemFailureReason.INTERNAL_STATE_ERROR, message);
    }
}
