package badapodo.sugang.controller;

import badapodo.sugang.controller.request.GlobalInMemoryEnrollmentRequest;
import badapodo.sugang.controller.response.GlobalInMemorySingleWriterEnrollmentResponse;
import badapodo.sugang.service.globalinmemory.GlobalInMemoryCommandResponse;
import badapodo.sugang.service.globalinmemory.GlobalInMemorySingleWriterEnrollmentService;
import badapodo.sugang.service.globalinmemory.GlobalSingleWriterSystemException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/global-in-memory-single-writer/enrollments")
@RequiredArgsConstructor
public class GlobalInMemorySingleWriterEnrollmentController {

    private final GlobalInMemorySingleWriterEnrollmentService enrollmentService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<GlobalInMemorySingleWriterEnrollmentResponse> enroll(
            @RequestBody byte[] payload,
            @RequestHeader(value = "X-Scenario-Type", required = false) String scenarioType
    ) {
        GlobalInMemoryEnrollmentRequest request;
        try {
            request = objectMapper.readValue(payload, GlobalInMemoryEnrollmentRequest.class);
        } catch (IOException e) {
            return GlobalInMemorySingleWriterEnrollmentResponseMapper.invalid(
                    "INVALID_JSON",
                    "요청 JSON 형식이 올바르지 않습니다."
            );
        }

        ResponseEntity<GlobalInMemorySingleWriterEnrollmentResponse> invalidResponse = validate(request);
        if (invalidResponse != null) {
            return invalidResponse;
        }

        UUID commandId = resolveCommandId(request.getCommandId());
        if (commandId == null) {
            return GlobalInMemorySingleWriterEnrollmentResponseMapper.invalid(
                    "INVALID_COMMAND_ID",
                    "commandId는 UUID 형식이어야 합니다."
            );
        }

        try {
            GlobalInMemoryCommandResponse response = enrollmentService.enroll(
                    commandId,
                    request.getStudentId(),
                    request.getCourseId(),
                    scenarioType
            );
            return GlobalInMemorySingleWriterEnrollmentResponseMapper.from(response);
        } catch (GlobalSingleWriterSystemException e) {
            return GlobalInMemorySingleWriterEnrollmentResponseMapper.failed(commandId.toString(), e);
        }
    }

    private ResponseEntity<GlobalInMemorySingleWriterEnrollmentResponse> validate(
            GlobalInMemoryEnrollmentRequest request
    ) {
        if (request.getStudentId() == null) {
            return GlobalInMemorySingleWriterEnrollmentResponseMapper.invalid(
                    "MISSING_STUDENT_ID",
                    "studentId는 필수 값입니다."
            );
        }
        if (request.getCourseId() == null) {
            return GlobalInMemorySingleWriterEnrollmentResponseMapper.invalid(
                    "MISSING_COURSE_ID",
                    "courseId는 필수 값입니다."
            );
        }
        if (request.getStudentId() <= 0) {
            return GlobalInMemorySingleWriterEnrollmentResponseMapper.invalid(
                    "INVALID_STUDENT_ID",
                    "studentId는 1 이상이어야 합니다."
            );
        }
        if (request.getCourseId() <= 0) {
            return GlobalInMemorySingleWriterEnrollmentResponseMapper.invalid(
                    "INVALID_COURSE_ID",
                    "courseId는 1 이상이어야 합니다."
            );
        }
        return null;
    }

    private UUID resolveCommandId(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(commandId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
