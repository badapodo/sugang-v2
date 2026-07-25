package badapodo.sugang.service.globalinmemory;

public abstract class GlobalSingleWriterSystemException extends RuntimeException {

    private final GlobalSingleWriterSystemFailureReason reason;

    protected GlobalSingleWriterSystemException(
            GlobalSingleWriterSystemFailureReason reason,
            String message
    ) {
        super(message);
        this.reason = reason;
    }

    protected GlobalSingleWriterSystemException(
            GlobalSingleWriterSystemFailureReason reason,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.reason = reason;
    }

    public GlobalSingleWriterSystemFailureReason getReason() {
        return reason;
    }
}
