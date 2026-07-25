package badapodo.sugang.service.globalinmemory;

public enum GlobalSingleWriterSystemFailureReason {
    COMMAND_PROCESSING_FAILED,
    COMMAND_TIMEOUT,
    WRITER_UNAVAILABLE,
    INTERNAL_STATE_ERROR
}
