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
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uk_student_completed_course", columnNames = { "student_id", "course_id" })
})
public class CompletedCourse extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long studentId; // 학생 ID

    @Column(nullable = false)
    private Long courseId; // 이수한 강의 ID

    public static CompletedCourse create(Long studentId, Long courseId) {
        CompletedCourse completedCourse = new CompletedCourse();
        completedCourse.studentId = studentId;
        completedCourse.courseId = courseId;
        return completedCourse;
    }
}
