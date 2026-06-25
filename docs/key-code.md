# Key Code Guide

## 핵심 의도

Baseline 구현의 핵심은 멋진 구조가 아니라 “동기식 RDB 구조가 Hotspot 요청에서 어디서 무너지는지”를 측정할 수 있게 만드는 것이다.

따라서 코드는 일부러 단순하게 유지한다.

```text
Controller
→ Service @Transactional
→ Repository SELECT ... FOR UPDATE
→ PostgreSQL row lock
```

## 1. BaselineEnrollmentController

파일:

```text
src/main/java/badapodo/sugang/controller/BaselineEnrollmentController.java
```

역할:

- 인증 없는 Baseline API 제공
- k6 payload의 `studentId`, `courseId`를 직접 받음
- 성공 시 HTTP 200과 `{ "status": "SUCCESS" }` 반환

중요한 이유:

Baseline에서는 JWT 인증 병목을 제외하고 수강신청 트랜잭션 병목만 측정해야 한다.

## 2. BaselineEnrollmentService

파일:

```text
src/main/java/badapodo/sugang/service/BaselineEnrollmentService.java
```

역할:

- 하나의 `@Transactional` 안에서 수강신청 전체 처리
- Course row를 비관적 락으로 조회
- 정원, 중복, 선수과목, 시간표 충돌 검증
- Enrollment 저장
- Course currentCount 증가

처리 순서:

```text
1. Course 조회 + PESSIMISTIC_WRITE lock
2. 정원 확인
3. 중복 신청 확인
4. 선수과목 확인
5. 시간표 충돌 확인
6. Enrollment 저장
7. currentCount 증가
```

이 순서는 성능 테스트에서 매우 중요하다. Hotspot 과목에 요청이 몰리면 1번 단계의 row lock에서 경합이 발생한다.

## 3. CourseRepository

파일:

```text
src/main/java/badapodo/sugang/repository/CourseRepository.java
```

핵심 코드:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Course c where c.id = :id")
Optional<Course> findByIdWithPessimisticLock(Long id);
```

의미:

JPA가 PostgreSQL에서 `SELECT ... FOR UPDATE`에 해당하는 쿼리를 실행하도록 한다.

이 락이 있어야 동시에 같은 과목을 신청해도 `currentCount`가 정원 이상으로 증가하지 않는다.

## 4. Course

파일:

```text
src/main/java/badapodo/sugang/domain/Course.java
```

핵심 필드:

| 필드 | 의미 |
| --- | --- |
| `capacity` | 과목 정원 |
| `currentCount` | 현재 신청 인원 |
| `version` | JPA optimistic lock용 version 컬럼. Baseline에서는 pessimistic lock이 주 경로 |

핵심 메서드:

```java
public void enroll() {
    if (currentCount >= capacity) {
        throw new CapacityExcessException();
    }
    this.currentCount++;
}
```

서비스에서 사전 정원 검증을 한 번 수행하고, 엔티티 메서드에서 다시 방어한다.

## 5. Enrollment

파일:

```text
src/main/java/badapodo/sugang/domain/Enrollment.java
```

핵심 제약:

```java
@UniqueConstraint(name = "uk_student_course", columnNames = {"student_id", "course_id"})
```

의미:

애플리케이션 검증이 있어도 DB unique constraint로 동일 학생의 동일 과목 중복 신청을 최종 방어한다.

## 6. 시간표 충돌 검증

관련 파일:

```text
src/main/java/badapodo/sugang/repository/EnrollmentRepository.java
src/main/java/badapodo/sugang/repository/CourseTimeRepository.java
src/main/java/badapodo/sugang/service/BaselineEnrollmentService.java
```

검증 방식:

1. 신청하려는 과목의 `course_time` 조회
2. 학생이 이미 신청한 과목 ID 목록 조회
3. 기존 신청 과목들의 `course_time` 조회
4. 요일이 같고 시간이 겹치면 실패

시간 겹침 조건:

```text
left.start < right.end
AND
right.start < left.end
```

## 7. 선수과목 검증

관련 파일:

```text
src/main/java/badapodo/sugang/repository/PrerequisiteRepository.java
src/main/java/badapodo/sugang/repository/CompletedCourseRepository.java
```

검증 방식:

1. 학생의 학과 조회
2. 신청 과목 + 학과 기준 선수과목 목록 조회
3. 학생의 completed_course 목록과 비교
4. 누락된 선수과목이 있으면 `PrerequisiteNotMetException`

## 8. DB Schema / Index

파일:

```text
infra/postgres/schema.sql
```

중요한 DB 제약:

| 제약/인덱스 | 목적 |
| --- | --- |
| `uk_student_course` | 중복 신청 최종 방어 |
| `idx_enrollment_student` | 학생별 기존 신청 조회 |
| `idx_enrollment_course` | 과목별 신청 결과 분석 |
| `idx_completed_course_student` | 선수과목 검증 성능 |
| `idx_prerequisite_covering` | 과목+학과별 선수과목 조회 |
| `idx_course_time_course` | 과목 시간표 조회 |

## 9. k6 부하 테스트

파일:

```text
k6/baseline-enrollment.js
```

역할:

- `enrollment_payload.csv`를 `SharedArray`로 로드
- `scheduled_offset_ms` 기준으로 요청 시간 분산
- `scenario_type`을 k6 tag로 남김
- 성공 payload는 HTTP 200, 실패 payload는 HTTP 4xx인지 확인

## 10. 이 구현으로 검증할 수 있는 것

| 검증 항목 | 확인 방법 |
| --- | --- |
| 정원 초과 방어 | course.current_count가 capacity를 넘지 않는지 확인 |
| 중복 신청 방어 | enrollment의 `(student_id, course_id)` unique constraint |
| Hotspot 병목 | k6 P95/P99, PostgreSQL lock wait |
| 커넥션 병목 | Hikari active/pending metrics |
| 도메인 실패 방어 | prerequisite/time-conflict payload가 4xx로 반환되는지 확인 |
