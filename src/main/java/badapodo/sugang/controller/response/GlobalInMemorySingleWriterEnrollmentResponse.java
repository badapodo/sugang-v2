package badapodo.sugang.controller.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GlobalInMemorySingleWriterEnrollmentResponse(
        String commandId,
        String status,
        String reason,
        Long studentId,
        Long courseId,
        String message
) {
}
