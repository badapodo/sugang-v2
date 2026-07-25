package badapodo.sugang.service.globalinmemory;

public class WriterUnavailableException extends GlobalSingleWriterSystemException {

    public WriterUnavailableException(String message) {
        super(GlobalSingleWriterSystemFailureReason.WRITER_UNAVAILABLE, message);
    }
}
