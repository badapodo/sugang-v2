package badapodo.sugang.controller;

import badapodo.sugang.controller.request.BaselineEnrollmentRequest;
import badapodo.sugang.controller.response.BaselineEnrollmentResponse;
import badapodo.sugang.service.BaselineEnrollmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/baseline/enrollments")
@RequiredArgsConstructor
public class BaselineEnrollmentController {

    private final BaselineEnrollmentService baselineEnrollmentService;

    @PostMapping
    public ResponseEntity<BaselineEnrollmentResponse> enroll(
            @Valid @RequestBody BaselineEnrollmentRequest request
    ) {
        baselineEnrollmentService.enroll(request.getStudentId(), request.getCourseId());
        return ResponseEntity.ok(BaselineEnrollmentResponse.of("SUCCESS"));
    }
}
