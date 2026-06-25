package badapodo.sugang.repository;

import badapodo.sugang.domain.CompletedCourse;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompletedCourseRepository extends JpaRepository<CompletedCourse, Long> {

    @Query("select c.courseId from CompletedCourse c where c.studentId = :studentId")
    Set<Long> findCourseIdsByStudentId(@Param("studentId") Long studentId);

    boolean existsByStudentIdAndCourseId(Long studentId, Long courseId);
}
