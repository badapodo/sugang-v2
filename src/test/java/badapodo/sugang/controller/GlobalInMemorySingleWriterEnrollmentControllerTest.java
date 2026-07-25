package badapodo.sugang.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import badapodo.sugang.service.globalinmemory.CommandProcessingException;
import badapodo.sugang.service.globalinmemory.CommandTimeoutException;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryCommandResponse;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryEnrollmentAccepted;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryEnrollmentRejected;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryEnrollmentRejectionReason;
import badapodo.sugang.service.globalinmemory.GlobalInMemorySingleWriterEnrollmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalInMemorySingleWriterEnrollmentControllerTest {

    private final GlobalInMemorySingleWriterEnrollmentService service =
            mock(GlobalInMemorySingleWriterEnrollmentService.class);
    private final GlobalInMemorySingleWriterEnrollmentController controller =
            new GlobalInMemorySingleWriterEnrollmentController(service, new ObjectMapper());

    @Test
    void returnsCreatedForAcceptedCommand() {
        UUID commandId = UUID.randomUUID();
        when(service.enroll(commandId, 1001L, 20L, null))
                .thenReturn(GlobalInMemoryCommandResponse.completed(
                        GlobalInMemoryEnrollmentAccepted.of(commandId, 1001L, 20L)
                ));

        var response = controller.enroll(json(commandId, 1001L, 20L), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo("ACCEPTED");
        assertThat(response.getBody().commandId()).isEqualTo(commandId.toString());
    }

    @Test
    void mapsCourseNotFoundToNotFound() {
        UUID commandId = UUID.randomUUID();
        when(service.enroll(commandId, 1001L, 20L, null))
                .thenReturn(GlobalInMemoryCommandResponse.completed(
                        GlobalInMemoryEnrollmentRejected.of(
                                commandId,
                                1001L,
                                20L,
                                GlobalInMemoryEnrollmentRejectionReason.COURSE_NOT_FOUND
                        )
                ));

        var response = controller.enroll(json(commandId, 1001L, 20L), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().status()).isEqualTo("REJECTED");
        assertThat(response.getBody().reason()).isEqualTo("COURSE_NOT_FOUND");
    }

    @Test
    void rejectsInvalidStudentIdBeforeWriter() {
        var response = controller.enroll("{\"studentId\":0,\"courseId\":20}".getBytes(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().status()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().reason()).isEqualTo("INVALID_STUDENT_ID");
    }

    @Test
    void rejectsInvalidCourseIdBeforeWriter() {
        var response = controller.enroll("{\"studentId\":1001,\"courseId\":0}".getBytes(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().reason()).isEqualTo("INVALID_COURSE_ID");
    }

    @Test
    void rejectsInvalidCommandIdBeforeWriter() {
        var response = controller.enroll(
                "{\"commandId\":\"bad\",\"studentId\":1001,\"courseId\":20}".getBytes(),
                null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().reason()).isEqualTo("INVALID_COMMAND_ID");
    }

    @Test
    void mapsCommandIdConflictToConflict() {
        UUID commandId = UUID.randomUUID();
        when(service.enroll(commandId, 1002L, 20L, null))
                .thenReturn(GlobalInMemoryCommandResponse.commandIdConflict(commandId));

        var response = controller.enroll(json(commandId, 1002L, 20L), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().reason()).isEqualTo("COMMAND_ID_CONFLICT");
    }

    @Test
    void mapsQueueFullToServiceUnavailableAndWriterUnavailable() {
        UUID commandId = UUID.randomUUID();
        when(service.enroll(commandId, 1001L, 20L, null))
                .thenReturn(GlobalInMemoryCommandResponse.queueFull(commandId));

        var response = controller.enroll(json(commandId, 1001L, 20L), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().status()).isEqualTo("FAILED");
        assertThat(response.getBody().reason()).isEqualTo("WRITER_UNAVAILABLE");
        assertThat(response.getBody().message()).isEqualTo("현재 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.");
        assertThat(response.getHeaders()).doesNotContainKey("Retry-After");
    }

    @Test
    void mapsCommandTimeoutToServiceUnavailableAndCommandTimeout() {
        UUID commandId = UUID.randomUUID();
        when(service.enroll(commandId, 1001L, 20L, null))
                .thenThrow(new CommandTimeoutException("요청 처리 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."));

        var response = controller.enroll(json(commandId, 1001L, 20L), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().status()).isEqualTo("FAILED");
        assertThat(response.getBody().reason()).isEqualTo("COMMAND_TIMEOUT");
        assertThat(response.getBody().message()).isEqualTo("요청 처리 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.");
    }

    @Test
    void queueFullAndTimeoutShareStatusButKeepDifferentReasons() {
        UUID queueFullCommandId = UUID.randomUUID();
        UUID timeoutCommandId = UUID.randomUUID();
        when(service.enroll(queueFullCommandId, 1001L, 20L, null))
                .thenReturn(GlobalInMemoryCommandResponse.queueFull(queueFullCommandId));
        when(service.enroll(timeoutCommandId, 1001L, 20L, null))
                .thenThrow(new CommandTimeoutException("요청 처리 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."));

        var queueFullResponse = controller.enroll(json(queueFullCommandId, 1001L, 20L), null);
        var timeoutResponse = controller.enroll(json(timeoutCommandId, 1001L, 20L), null);

        assertThat(queueFullResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(timeoutResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(queueFullResponse.getBody().reason()).isEqualTo("WRITER_UNAVAILABLE");
        assertThat(timeoutResponse.getBody().reason()).isEqualTo("COMMAND_TIMEOUT");
    }

    @Test
    void mapsSystemExceptionToFailedResponse() {
        UUID commandId = UUID.randomUUID();
        when(service.enroll(commandId, 1001L, 20L, null))
                .thenThrow(new CommandProcessingException("boom", new IllegalStateException("boom")));

        var response = controller.enroll(json(commandId, 1001L, 20L), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().status()).isEqualTo("FAILED");
        assertThat(response.getBody().reason()).isEqualTo("COMMAND_PROCESSING_FAILED");
    }

    private byte[] json(UUID commandId, Long studentId, Long courseId) {
        return ("""
                {"commandId":"%s","studentId":%d,"courseId":%d}
                """.formatted(commandId, studentId, courseId)).getBytes();
    }
}
