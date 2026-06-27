package badapodo.sugang.service.inmemory;

import badapodo.sugang.exception.CapacityExcessException;
import badapodo.sugang.exception.DuplicateEnrollmentException;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;

@Getter
public class InMemoryCourseState {

    private final Long courseId;
    private final int capacity;
    private int currentCount;
    private int remainingCapacity;
    private final Set<Long> enrolledStudentIds;

    public InMemoryCourseState(Long courseId, int capacity, int currentCount, Set<Long> enrolledStudentIds) {
        this.courseId = courseId;
        this.capacity = capacity;
        this.currentCount = Math.max(0, Math.min(capacity, currentCount));
        this.remainingCapacity = Math.max(0, capacity - this.currentCount);
        this.enrolledStudentIds = new HashSet<>(enrolledStudentIds);
    }

    public void enroll(Long studentId) {
        if (enrolledStudentIds.contains(studentId)) {
            throw new DuplicateEnrollmentException();
        }

        if (remainingCapacity <= 0) {
            throw new CapacityExcessException();
        }

        enrolledStudentIds.add(studentId);
        currentCount++;
        remainingCapacity--;
    }
}
