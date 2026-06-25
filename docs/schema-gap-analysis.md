# Schema Gap Analysis

## 결론

`tmp/sugang`의 기존 엔티티 중 Mock Data Harness와 Baseline에 필요한 엔티티만 `sugang-v2`로 가져왔다.

Redis, JWT, 분산락, 캐시 워밍 관련 코드는 Baseline 목적과 맞지 않아 제외했다.

## 가져온 엔티티

| 테이블 | 엔티티 | 상태 |
| --- | --- | --- |
| department | Department | 사용 |
| member | Member | Mock student 참조용으로 사용 |
| student | Student | 사용 |
| course | Course | 사용 |
| course_time | CourseTime | 시간표 충돌 검증에 사용 |
| prerequisite | Prerequisite | 선수과목 검증에 사용 |
| completed_course | CompletedCourse | 선수과목/이미 이수한 과목 검증에 사용 |
| enrollment | Enrollment | 수강신청 결과 저장 |

## Mock CSV와 DB 컬럼

Mock Harness의 `config/schema-map.yaml` 기준으로 PostgreSQL schema를 작성했다.

CSV 적재 대상:

```text
mock/sugang-mock/output/csv/department.csv
mock/sugang-mock/output/csv/member.csv
mock/sugang-mock/output/csv/course.csv
mock/sugang-mock/output/csv/student.csv
mock/sugang-mock/output/csv/course_time.csv
mock/sugang-mock/output/csv/prerequisite.csv
mock/sugang-mock/output/csv/completed_course.csv
mock/sugang-mock/output/csv/enrollment.csv
```

성능 테스트 payload:

```text
mock/sugang-mock/output/csv/enrollment_payload.csv
```

## Baseline에서 추가로 명시한 제약조건

| 제약 | 목적 |
| --- | --- |
| `uk_student_course` | 동일 학생의 동일 과목 중복 신청 방어 |
| `uk_student_completed_course` | completed_course 중복 방어 |
| FK: enrollment → student/course | 잘못된 payload 적재 방어 |
| FK: course_time/prerequisite → course | 시간표/선수과목 참조 무결성 |

## 성능 테스트용 인덱스

| 인덱스 | 목적 |
| --- | --- |
| `idx_enrollment_student` | 학생의 기존 신청 과목 조회 |
| `idx_enrollment_course` | 과목별 신청 결과 분석 |
| `idx_completed_course_student` | 선수과목 이수 여부 조회 |
| `idx_prerequisite_course` | 신청 과목의 선수과목 조회 |
| `idx_prerequisite_covering` | course + department + pre_course 조회 |
| `idx_course_time_course` | 과목별 시간표 조회 |

## 의도적으로 제외한 기존 구현

| 제외 항목 | 이유 |
| --- | --- |
| RedissonConfig | Baseline은 Redis 없이 DB row lock만 측정 |
| DistributedLock AOP | 분산락은 V2 개선안의 비교 대상 |
| ScheduleCacheService | Redis bitmask 대신 DB course_time으로 충돌 검증 |
| Auth/JWT/Security | 인증 병목을 제거하고 수강신청 트랜잭션만 측정 |
| RedisLuaScriptService | Baseline 범위 밖 |

## 남은 확인 포인트

- 운영 API가 필요해지는 단계에서는 `/api/baseline/enrollments`와 별도의 인증 API를 분리한다.
- V2에서 Redis Lock 또는 Redis 기반 시간표 bitmask를 추가할 때 이 Baseline 결과와 비교한다.
