package badapodo.sugang.service.globalinmemory;

public enum GlobalInMemoryEnrollmentRejectionReason {
    STUDENT_NOT_FOUND("학생 정보를 찾을 수 없습니다."),
    COURSE_NOT_FOUND("강의를 찾을 수 없습니다."),
    CAPACITY_EXCEEDED("수강 정원이 초과되었습니다."),
    DUPLICATE_ENROLLMENT("현재 학기에 이미 신청한 과목입니다."),
    PREREQUISITE_NOT_MET("선수 과목을 수강하지 않았습니다."),
    SCHEDULE_CONFLICT("시간표가 중복되는 강의가 있습니다.");

    private final String message;

    GlobalInMemoryEnrollmentRejectionReason(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
