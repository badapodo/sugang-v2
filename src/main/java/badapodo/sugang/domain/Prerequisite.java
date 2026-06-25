package badapodo.sugang.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)

@Table(name = "prerequisite", indexes = {
        @Index(name = "idx_prerequisite_covering", columnList = "course_id, department_id, pre_course_id")
})
public class Prerequisite extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pre_course_id")
    private Course preCourse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    // 현재는 선수과목은 학과별로 명시적으로 관리한다고 가정
    public static Prerequisite create(Course course, Course preCourse, Department department) {
        Prerequisite prerequisite = new Prerequisite();
        prerequisite.course = course;
        prerequisite.preCourse = preCourse;
        prerequisite.department = department;
        return prerequisite;
    }
}
