package badapodo.sugang.service.globalinmemory;

import badapodo.sugang.domain.CompletedCourse;
import badapodo.sugang.domain.Course;
import badapodo.sugang.domain.CourseTime;
import badapodo.sugang.domain.Enrollment;
import badapodo.sugang.domain.Prerequisite;
import badapodo.sugang.domain.Student;
import badapodo.sugang.exception.AlreadyCompletedCourseException;
import badapodo.sugang.exception.DuplicateEnrollmentException;
import badapodo.sugang.exception.PrerequisiteNotMetException;
import badapodo.sugang.exception.TimeConflictException;
import badapodo.sugang.repository.CompletedCourseRepository;
import badapodo.sugang.repository.CourseRepository;
import badapodo.sugang.repository.CourseTimeRepository;
import badapodo.sugang.repository.EnrollmentRepository;
import badapodo.sugang.repository.PrerequisiteRepository;
import badapodo.sugang.repository.StudentRepository;
import badapodo.sugang.service.inmemory.InMemoryCourseTime;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalInMemoryStateStore {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CompletedCourseRepository completedCourseRepository;
    private final PrerequisiteRepository prerequisiteRepository;
    private final CourseTimeRepository courseTimeRepository;
    private final StudentRepository studentRepository;

    // After startup, these mutable structures are owned exclusively by the global writer thread.
    private final Map<Long, GlobalCourseState> courseStates = new HashMap<>();
    private final Map<Long, Long> studentDepartmentIds = new HashMap<>();
    private final Map<Long, Set<Long>> completedCourseIdsByStudentId = new HashMap<>();
    private final Map<String, Set<Long>> prerequisiteCourseIdsByCourseAndDepartment = new HashMap<>();
    private final Map<Long, List<InMemoryCourseTime>> courseTimesByCourseId = new HashMap<>();
    private final Map<Long, Set<Long>> enrolledCourseIdsByStudentId = new HashMap<>();
    private final Map<Long, List<InMemoryCourseTime>> studentTimetables = new HashMap<>();

    @PostConstruct
    @Transactional(readOnly = true)
    public void loadState() {
        List<Course> courses = courseRepository.findAll();
        List<Enrollment> enrollments = enrollmentRepository.findAll();

        loadStudentDepartments();
        loadCompletedCourses();
        loadPrerequisites();
        loadCourseTimes();
        loadEnrollments(enrollments);

        Map<Long, Long> enrollmentCountsByCourseId = enrollments.stream()
                .collect(Collectors.groupingBy(Enrollment::getCourseId, Collectors.counting()));
        courseStates.clear();
        for (Course course : courses) {
            int currentCount = Math.max(
                    course.getCurrentCount(),
                    enrollmentCountsByCourseId.getOrDefault(course.getId(), 0L).intValue()
            );
            courseStates.put(course.getId(), new GlobalCourseState(course.getCapacity(), currentCount));
        }

        log.info(
                "Loaded global in-memory single-writer state. courses={}, students={}, enrolledStudents={}, timetables={}",
                courseStates.size(),
                studentDepartmentIds.size(),
                enrolledCourseIdsByStudentId.size(),
                studentTimetables.size()
        );
    }

    public void validateAndEnroll(Long studentId, Long courseId) {
        validatePrerequisite(studentId, courseId);
        validateDuplicate(studentId, courseId);
        validateTimeConflict(studentId, courseId);

        GlobalCourseState courseState = courseStates.get(courseId);
        if (courseState == null) {
            throw new IllegalArgumentException("강의를 찾을 수 없습니다.");
        }
        courseState.validateCapacity();

        courseState.enroll();
        enrolledCourseIdsByStudentId.computeIfAbsent(studentId, ignored -> new HashSet<>()).add(courseId);
        studentTimetables.computeIfAbsent(studentId, ignored -> new ArrayList<>())
                .addAll(courseTimesByCourseId.getOrDefault(courseId, Collections.emptyList()));
    }

    private void validatePrerequisite(Long studentId, Long courseId) {
        Long departmentId = studentDepartmentIds.get(studentId);
        if (departmentId == null) {
            throw new IllegalArgumentException("학생 정보를 찾을 수 없습니다.");
        }

        Set<Long> requiredCourseIds = prerequisiteCourseIdsByCourseAndDepartment.getOrDefault(
                prerequisiteKey(courseId, departmentId),
                Collections.emptySet()
        );
        Set<Long> completedCourseIds = completedCourseIdsByStudentId.getOrDefault(studentId, Collections.emptySet());
        for (Long requiredCourseId : requiredCourseIds) {
            if (!completedCourseIds.contains(requiredCourseId)) {
                throw new PrerequisiteNotMetException();
            }
        }
    }

    private void validateDuplicate(Long studentId, Long courseId) {
        if (enrolledCourseIdsByStudentId.getOrDefault(studentId, Collections.emptySet()).contains(courseId)) {
            throw new DuplicateEnrollmentException();
        }
        if (completedCourseIdsByStudentId.getOrDefault(studentId, Collections.emptySet()).contains(courseId)) {
            throw new AlreadyCompletedCourseException();
        }
    }

    private void validateTimeConflict(Long studentId, Long courseId) {
        List<InMemoryCourseTime> requestedTimes = courseTimesByCourseId.getOrDefault(
                courseId,
                Collections.emptyList()
        );
        List<InMemoryCourseTime> enrolledTimes = studentTimetables.getOrDefault(
                studentId,
                Collections.emptyList()
        );
        for (InMemoryCourseTime requestedTime : requestedTimes) {
            for (InMemoryCourseTime enrolledTime : enrolledTimes) {
                if (requestedTime.overlaps(enrolledTime)) {
                    throw new TimeConflictException();
                }
            }
        }
    }

    private void loadStudentDepartments() {
        studentDepartmentIds.clear();
        for (Student student : studentRepository.findAllWithDepartment()) {
            studentDepartmentIds.put(student.getId(), student.getDepartment().getId());
        }
    }

    private void loadCompletedCourses() {
        completedCourseIdsByStudentId.clear();
        completedCourseIdsByStudentId.putAll(completedCourseRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        CompletedCourse::getStudentId,
                        Collectors.mapping(CompletedCourse::getCourseId, Collectors.toCollection(HashSet::new))
                )));
    }

    private void loadPrerequisites() {
        prerequisiteCourseIdsByCourseAndDepartment.clear();
        for (Prerequisite prerequisite : prerequisiteRepository.findAllWithRelations()) {
            String key = prerequisiteKey(prerequisite.getCourse().getId(), prerequisite.getDepartment().getId());
            prerequisiteCourseIdsByCourseAndDepartment.computeIfAbsent(key, ignored -> new HashSet<>())
                    .add(prerequisite.getPreCourse().getId());
        }
    }

    private void loadCourseTimes() {
        courseTimesByCourseId.clear();
        courseTimesByCourseId.putAll(courseTimeRepository.findAllWithCourse().stream()
                .collect(Collectors.groupingBy(
                        courseTime -> courseTime.getCourse().getId(),
                        Collectors.mapping(this::toInMemoryCourseTime, Collectors.toList())
                )));
    }

    private void loadEnrollments(List<Enrollment> enrollments) {
        enrolledCourseIdsByStudentId.clear();
        studentTimetables.clear();
        for (Enrollment enrollment : enrollments) {
            enrolledCourseIdsByStudentId.computeIfAbsent(enrollment.getStudentId(), ignored -> new HashSet<>())
                    .add(enrollment.getCourseId());
            studentTimetables.computeIfAbsent(enrollment.getStudentId(), ignored -> new ArrayList<>())
                    .addAll(courseTimesByCourseId.getOrDefault(enrollment.getCourseId(), Collections.emptyList()));
        }
    }

    private InMemoryCourseTime toInMemoryCourseTime(CourseTime courseTime) {
        return InMemoryCourseTime.of(
                courseTime.getCourse().getId(),
                courseTime.getDayOfWeek(),
                courseTime.getStartTime(),
                courseTime.getEndTime()
        );
    }

    private String prerequisiteKey(Long courseId, Long departmentId) {
        return courseId + ":" + departmentId;
    }
}
