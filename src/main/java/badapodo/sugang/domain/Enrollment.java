package badapodo.sugang.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_student_course", columnNames = {"student_id", "course_id"})
        }
)
public class Enrollment extends BaseEntity{
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long studentId;         // 학생 ID

    @Column(nullable = false)
    private Long courseId;          // 강의 ID
    
    public static Enrollment create(Long studentId, Long courseId) {
        Enrollment enrollment = new Enrollment();
        enrollment.studentId = studentId;
        enrollment.courseId = courseId;
        return enrollment;
    }
}
