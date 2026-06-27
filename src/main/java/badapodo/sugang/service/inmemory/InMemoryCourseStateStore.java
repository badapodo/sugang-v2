package badapodo.sugang.service.inmemory;

import badapodo.sugang.domain.Course;
import badapodo.sugang.domain.CourseTime;
import badapodo.sugang.domain.CompletedCourse;
import badapodo.sugang.domain.Enrollment;
import badapodo.sugang.domain.Prerequisite;
import badapodo.sugang.domain.Student;
import badapodo.sugang.exception.AlreadyCompletedCourseException;
import badapodo.sugang.exception.DuplicateEnrollmentException;
import badapodo.sugang.exception.PrerequisiteNotMetException;
import badapodo.sugang.exception.TimeConflictException;
import badapodo.sugang.repository.CompletedCourseRepository;
import badapodo.sugang.repository.CourseTimeRepository;
import badapodo.sugang.repository.CourseRepository;
import badapodo.sugang.repository.EnrollmentRepository;
import badapodo.sugang.repository.PrerequisiteRepository;
import badapodo.sugang.repository.StudentRepository;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InMemoryCourseStateStore {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CompletedCourseRepository completedCourseRepository;
    private final PrerequisiteRepository prerequisiteRepository;
    private final CourseTimeRepository courseTimeRepository;
    private final StudentRepository studentRepository;
    private final Map<Long, InMemoryCourseState> courseStates = new ConcurrentHashMap<>();
    private final Map<Long, Long> studentDepartmentIds = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> completedCourseIdsByStudentId = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> prerequisiteCourseIdsByCourseAndDepartment = new ConcurrentHashMap<>();
    private final Map<Long, List<InMemoryCourseTime>> courseTimesByCourseId = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> enrolledCourseIdsByStudentId = new ConcurrentHashMap<>();
    private final Map<Long, Object> studentLocks = new ConcurrentHashMap<>();

    @PostConstruct
    @Transactional(readOnly = true)
    public void loadCourseStates() {
        List<Course> courses = courseRepository.findAll();
        List<Enrollment> enrollments = enrollmentRepository.findAll();
        loadStudentDepartments();
        loadCompletedCourses();
        loadPrerequisites();
        loadCourseTimes();

        Map<Long, Set<Long>> enrolledStudentIdsByCourseId = enrollments.stream()
                .collect(Collectors.groupingBy(Enrollment::getCourseId, Collectors.mapping(Enrollment::getStudentId, Collectors.toSet())));
        enrolledCourseIdsByStudentId.clear();
        enrolledCourseIdsByStudentId.putAll(enrollments.stream()
                .collect(Collectors.groupingBy(Enrollment::getStudentId, Collectors.mapping(Enrollment::getCourseId, Collectors.toSet()))));

        for (Course course : courses) {
            Set<Long> enrolledStudentIds = enrolledStudentIdsByCourseId.getOrDefault(
                    course.getId(),
                    Collections.emptySet()
            );
            int initialCurrentCount = Math.max(course.getCurrentCount(), enrolledStudentIds.size());
            courseStates.put(
                    course.getId(),
                    new InMemoryCourseState(
                            course.getId(),
                            course.getCapacity(),
                            initialCurrentCount,
                            enrolledStudentIds
                    )
            );
        }

        log.info(
                "Loaded in-memory course states. courseCount={}, studentCount={}, completedStudentCount={}, prerequisiteKeyCount={}, courseTimeCourseCount={}",
                courseStates.size(),
                studentDepartmentIds.size(),
                completedCourseIdsByStudentId.size(),
                prerequisiteCourseIdsByCourseAndDepartment.size(),
                courseTimesByCourseId.size()
        );
    }

    public Optional<InMemoryCourseState> findByCourseId(Long courseId) {
        return Optional.ofNullable(courseStates.get(courseId));
    }

    public void validateAndEnroll(Long studentId, Long courseId) {
        Object studentLock = studentLocks.computeIfAbsent(studentId, ignored -> new Object());
        synchronized (studentLock) {
            validatePrerequisite(studentId, courseId);
            validateDuplicate(studentId, courseId);
            validateTimeConflict(studentId, courseId);

            InMemoryCourseState courseState = findByCourseId(courseId)
                    .orElseThrow(() -> new IllegalArgumentException("강의를 찾을 수 없습니다."));
            courseState.enroll(studentId);
            enrolledCourseIdsByStudentId.computeIfAbsent(studentId, ignored -> ConcurrentHashMap.newKeySet())
                    .add(courseId);
        }
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
        if (requiredCourseIds.isEmpty()) {
            return;
        }

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
        List<InMemoryCourseTime> requestedTimes = courseTimesByCourseId.getOrDefault(courseId, Collections.emptyList());
        if (requestedTimes.isEmpty()) {
            return;
        }

        Set<Long> enrolledCourseIds = enrolledCourseIdsByStudentId.getOrDefault(studentId, Collections.emptySet());
        if (enrolledCourseIds.isEmpty()) {
            return;
        }

        for (Long enrolledCourseId : enrolledCourseIds) {
            List<InMemoryCourseTime> enrolledTimes = courseTimesByCourseId.getOrDefault(enrolledCourseId, Collections.emptyList());
            for (InMemoryCourseTime requestedTime : requestedTimes) {
                for (InMemoryCourseTime enrolledTime : enrolledTimes) {
                    if (requestedTime.overlaps(enrolledTime)) {
                        throw new TimeConflictException();
                    }
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
        completedCourseIdsByStudentId.putAll(completedCourseRepository.findAll()
                .stream()
                .collect(Collectors.groupingBy(
                        CompletedCourse::getStudentId,
                        Collectors.mapping(CompletedCourse::getCourseId, Collectors.toCollection(HashSet::new))
                )));
    }

    private void loadPrerequisites() {
        prerequisiteCourseIdsByCourseAndDepartment.clear();
        for (Prerequisite prerequisite : prerequisiteRepository.findAllWithRelations()) {
            String key = prerequisiteKey(prerequisite.getCourse().getId(), prerequisite.getDepartment().getId());
            prerequisiteCourseIdsByCourseAndDepartment.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet())
                    .add(prerequisite.getPreCourse().getId());
        }
    }

    private void loadCourseTimes() {
        courseTimesByCourseId.clear();
        Map<Long, List<InMemoryCourseTime>> loadedCourseTimes = courseTimeRepository.findAllWithCourse()
                .stream()
                .collect(Collectors.groupingBy(
                        courseTime -> courseTime.getCourse().getId(),
                        Collectors.mapping(this::toInMemoryCourseTime, Collectors.toList())
                ));
        courseTimesByCourseId.putAll(loadedCourseTimes);
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
