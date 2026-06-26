package badapodo.sugang.service.singlewriter;

import badapodo.sugang.domain.Course;
import badapodo.sugang.domain.CourseTime;
import badapodo.sugang.domain.Enrollment;
import badapodo.sugang.exception.AlreadyCompletedCourseException;
import badapodo.sugang.exception.CapacityExcessException;
import badapodo.sugang.exception.DuplicateEnrollmentException;
import badapodo.sugang.exception.PrerequisiteNotMetException;
import badapodo.sugang.exception.TimeConflictException;
import badapodo.sugang.repository.CompletedCourseRepository;
import badapodo.sugang.repository.CourseRepository;
import badapodo.sugang.repository.CourseTimeRepository;
import badapodo.sugang.repository.EnrollmentRepository;
import badapodo.sugang.repository.PrerequisiteRepository;
import badapodo.sugang.repository.StudentRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SingleWriterEnrollmentProcessor {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final PrerequisiteRepository prerequisiteRepository;
    private final CompletedCourseRepository completedCourseRepository;
    private final StudentRepository studentRepository;
    private final CourseTimeRepository courseTimeRepository;

    @Transactional
    public void process(SingleWriterEnrollmentCommand command) {
        Long studentId = command.getStudentId();
        Long courseId = command.getCourseId();

        Course course = courseRepository.findByIdWithPessimisticLock(courseId)
                .orElseThrow(() -> new IllegalArgumentException("강의를 찾을 수 없습니다."));

        validateCapacity(course);
        validateDuplicate(studentId, courseId);
        validatePrerequisite(studentId, courseId);
        validateTimeConflict(studentId, courseId);

        enrollmentRepository.save(Enrollment.create(studentId, courseId));
        course.enroll();
    }

    private void validateCapacity(Course course) {
        if (course.getCurrentCount() >= course.getCapacity()) {
            throw new CapacityExcessException();
        }
    }

    private void validateDuplicate(Long studentId, Long courseId) {
        if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
            throw new DuplicateEnrollmentException();
        }

        if (completedCourseRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
            throw new AlreadyCompletedCourseException();
        }
    }

    private void validatePrerequisite(Long studentId, Long courseId) {
        Long departmentId = studentRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("학생 정보를 찾을 수 없습니다."))
                .getDepartment()
                .getId();

        List<Long> requiredCourseIds = prerequisiteRepository.findPreCourseIdsByCourseIdAndDepartmentId(
                courseId,
                departmentId
        );

        if (requiredCourseIds.isEmpty()) {
            return;
        }

        Set<Long> completedCourseIds = completedCourseRepository.findCourseIdsByStudentId(studentId);
        for (Long requiredCourseId : requiredCourseIds) {
            if (!completedCourseIds.contains(requiredCourseId)) {
                throw new PrerequisiteNotMetException();
            }
        }
    }

    private void validateTimeConflict(Long studentId, Long courseId) {
        List<CourseTime> requestedTimes = courseTimeRepository.findAllByCourseId(courseId);
        if (requestedTimes.isEmpty()) {
            return;
        }

        List<Long> enrolledCourseIds = enrollmentRepository.findCourseIdsByStudentId(studentId);
        if (enrolledCourseIds.isEmpty()) {
            return;
        }

        List<CourseTime> enrolledTimes = courseTimeRepository.findByCourseIdIn(enrolledCourseIds);
        for (CourseTime requestedTime : requestedTimes) {
            for (CourseTime enrolledTime : enrolledTimes) {
                if (overlaps(requestedTime, enrolledTime)) {
                    throw new TimeConflictException();
                }
            }
        }
    }

    private boolean overlaps(CourseTime left, CourseTime right) {
        if (left.getDayOfWeek() != right.getDayOfWeek()) {
            return false;
        }

        return left.getStartTime().isBefore(right.getEndTime())
                && right.getStartTime().isBefore(left.getEndTime());
    }
}
