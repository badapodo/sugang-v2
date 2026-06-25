package badapodo.sugang.repository;

import badapodo.sugang.domain.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    List<Enrollment> findByStudentId(Long studentId);
    boolean existsByStudentIdAndCourseId(Long studentId, Long courseId);

    @Query("select e.courseId from Enrollment e where e.studentId = :studentId")
    List<Long> findCourseIdsByStudentId(@Param("studentId") Long studentId);
}
