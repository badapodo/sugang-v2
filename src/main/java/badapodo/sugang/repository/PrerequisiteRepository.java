package badapodo.sugang.repository;

import badapodo.sugang.domain.Prerequisite;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrerequisiteRepository extends JpaRepository<Prerequisite, Long> {

//        @Query("select pc.id " +
//            "from Prerequisite p " +
//            "join p.preCourse pc " +
//            "where p.course.id = :courseId " +
//            "and p.department.id = :departmentId")
    @Query("select p.preCourse.id " + // pc.id 대신 직접 참조
            "from Prerequisite p " +
            "where p.course.id = :courseId " +
            "and p.department.id = :departmentId")
    List<Long> findPreCourseIdsByCourseIdAndDepartmentId(
            @Param("courseId") Long courseId,
            @Param("departmentId") Long departmentId);
}
