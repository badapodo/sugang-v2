package badapodo.sugang.controller;

import badapodo.sugang.controller.request.BaselineEnrollmentRequest;
import badapodo.sugang.controller.response.BaselineEnrollmentResponse;
import badapodo.sugang.service.OptimisticEnrollmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/optimistic/enrollments")
@RequiredArgsConstructor
public class OptimisticEnrollmentController {

    private final OptimisticEnrollmentService optimisticEnrollmentService;

    @PostMapping
    public ResponseEntity<BaselineEnrollmentResponse> enroll(
            @Valid @RequestBody BaselineEnrollmentRequest request
    ) {
        optimisticEnrollmentService.enroll(request.getStudentId(), request.getCourseId());
        return ResponseEntity.ok(BaselineEnrollmentResponse.of("SUCCESS"));
    }
}
