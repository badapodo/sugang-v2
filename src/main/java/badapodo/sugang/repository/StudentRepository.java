package badapodo.sugang.repository;

import badapodo.sugang.domain.Student;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StudentRepository extends JpaRepository<Student, Long> {

    @Query("select s from Student s join fetch s.department")
    List<Student> findAllWithDepartment();
}
