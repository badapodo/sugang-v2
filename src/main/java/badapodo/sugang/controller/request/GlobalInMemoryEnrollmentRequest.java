package badapodo.sugang.controller.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GlobalInMemoryEnrollmentRequest {

    private String commandId;
    private Long studentId;
    private Long courseId;
}
