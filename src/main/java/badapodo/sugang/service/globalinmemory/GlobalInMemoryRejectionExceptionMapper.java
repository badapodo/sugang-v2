package badapodo.sugang.service.globalinmemory;

import badapodo.sugang.exception.CapacityExcessException;
import badapodo.sugang.exception.DuplicateEnrollmentException;
import badapodo.sugang.exception.PrerequisiteNotMetException;
import badapodo.sugang.exception.TimeConflictException;

final class GlobalInMemoryRejectionExceptionMapper {

    private GlobalInMemoryRejectionExceptionMapper() {
    }

    static RuntimeException toLegacyException(GlobalInMemoryEnrollmentRejectionReason reason) {
        return switch (reason) {
            case CAPACITY_EXCEEDED -> new CapacityExcessException();
            case DUPLICATE_ENROLLMENT -> new DuplicateEnrollmentException();
            case PREREQUISITE_NOT_MET -> new PrerequisiteNotMetException();
            case SCHEDULE_CONFLICT -> new TimeConflictException();
            case STUDENT_NOT_FOUND -> new IllegalArgumentException("학생 정보를 찾을 수 없습니다.");
            case COURSE_NOT_FOUND -> new IllegalArgumentException("강의를 찾을 수 없습니다.");
        };
    }
}
