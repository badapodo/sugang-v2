package badapodo.sugang.repository;

import badapodo.sugang.domain.CourseTime;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseTimeRepository extends JpaRepository<CourseTime, Long> {
    @Query("SELECT ct FROM CourseTime ct WHERE ct.course.id = :courseId")
    List<CourseTime> findAllByCourseId(@Param("courseId") Long courseId);
    List<CourseTime> findByCourseIdIn(List<Long> courseIds);

    @Query("select ct from CourseTime ct join fetch ct.course")
    List<CourseTime> findAllWithCourse();
}
