package badapodo.sugang.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import badapodo.sugang.service.BaselineEnrollmentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineEnrollmentControllerContractTest {

    @Test
    void baselineControllerKeepsSuccessContract() {
        BaselineEnrollmentService service = mock(BaselineEnrollmentService.class);
        BaselineEnrollmentController controller = new BaselineEnrollmentController(service);

        var response = controller.enroll(new badapodo.sugang.controller.request.BaselineEnrollmentRequest(1001L, 20L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getStatus()).isEqualTo("SUCCESS");
        verify(service).enroll(1001L, 20L);
    }
}
