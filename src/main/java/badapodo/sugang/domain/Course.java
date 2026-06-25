package badapodo.sugang.domain;

import badapodo.sugang.exception.CapacityExcessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Course extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title; // 강의명

    @Column(nullable = false)
    private Integer capacity; // 전체 정원

    @Column(nullable = false)
    private Integer currentCount; // 현재 신청 인원 (0부터 시작)

    @Version
    private Long version;

    @Builder
    public Course(String title, Integer capacity) {
        this.title = title;
        this.capacity = capacity;
        currentCount = 0;
    }

    public void enroll() {
        if (currentCount >= capacity) {
            throw new CapacityExcessException();
        }
        this.currentCount++;
    }
}
