# PostgreSQL Slow Query Analysis

이 문서는 부하 테스트 중 PostgreSQL slow SQL을 수집하고, 느린 쿼리를 `EXPLAIN ANALYZE`로 확인하는 절차를 정리한다.

## 목적

부하 테스트 중 Spring Boot/JPA가 실행하는 SQL 가운데 50ms 이상 걸리는 쿼리를 PostgreSQL 로그에서 확인한다.

확인 흐름:

```text
PostgreSQL slow query log 활성화
→ k6 부하 테스트 실행
→ docker logs sugang-v2-baseline-db 확인
→ 느린 SQL을 EXPLAIN ANALYZE로 분석
```

## Step 1. Slow SQL 로그 활성화

실행 중인 PostgreSQL 컨테이너에 `log_min_duration_statement = 50ms`를 적용한다.

```bash
docker compose exec -T postgres psql -U user -d enrollment \
  -c "ALTER SYSTEM SET log_min_duration_statement = '50ms';"

docker compose exec -T postgres psql -U user -d enrollment \
  -c "SELECT pg_reload_conf();"

docker compose exec -T postgres psql -U user -d enrollment \
  -c "SHOW log_min_duration_statement;"
```

마지막 명령 결과가 `50ms`이면 적용된 상태다.

## Step 2. 부하 테스트 실행

로그 범위를 나중에 좁혀 보기 위해 테스트 시작 시각을 저장한다.

```bash
START_TIME=$(date --iso-8601=seconds)
```

반복 테스트 전 DB 상태를 초기화한다.

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql
```

Prometheus/Grafana에 k6 지표까지 적재하는 부하 테스트:

```bash
SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load-prometheus run --rm k6-prometheus
```

summary JSON만 필요한 경우:

```bash
SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load run --rm k6
```

Smoke test로 먼저 확인할 경우:

```bash
SCENARIO_FILTER=NORMAL LIMIT=100 VUS=1 IGNORE_SCHEDULE=true MAX_DURATION=30s \
docker compose --profile load-prometheus run --rm k6-prometheus
```

## Step 3. PostgreSQL 로그 확인

전체 PostgreSQL 로그:

```bash
docker logs sugang-v2-baseline-db
```

부하 테스트 시작 이후 로그만 확인:

```bash
docker logs --since "$START_TIME" sugang-v2-baseline-db
```

slow SQL 관련 로그만 필터링:

```bash
docker logs --since "$START_TIME" sugang-v2-baseline-db \
  | grep -E "duration:|statement:"
```

PostgreSQL slow query log는 보통 다음 형태로 출력된다.

```text
duration: 123.456 ms  execute <unnamed>: select ...
```

로그에서 확인할 것:

- `duration`이 큰 SQL
- 같은 SQL이 반복적으로 나타나는지 여부
- `select ... for update` 대기 또는 row lock 경합 가능성
- 인덱스가 필요한 조건절인지 여부
- 동일 학생/과목 검증 쿼리가 과도하게 반복되는지 여부

## Step 4. EXPLAIN ANALYZE 실행

로그에서 느린 SQL을 복사한 뒤 `psql`에 접속한다.

```bash
docker compose exec -T postgres psql -U user -d enrollment
```

쿼리에 `EXPLAIN (ANALYZE, BUFFERS)`를 붙여 실행한다.

예시:

```sql
EXPLAIN (ANALYZE, BUFFERS)
select c.*
from course c
where c.id = 123
for update;
```

중복 신청 검증 쿼리 예시:

```sql
EXPLAIN (ANALYZE, BUFFERS)
select exists (
  select 1
  from enrollment e
  where e.student_id = 1001
    and e.course_id = 20
);
```

강의 시간표 조회 쿼리 예시:

```sql
EXPLAIN (ANALYZE, BUFFERS)
select ct.*
from course_time ct
where ct.course_id = 20;
```

선수과목 조회 쿼리 예시:

```sql
EXPLAIN (ANALYZE, BUFFERS)
select p.pre_course_id
from prerequisite p
where p.course_id = 20
  and p.department_id = 1;
```

학생별 수강신청 과목 조회 예시:

```sql
EXPLAIN (ANALYZE, BUFFERS)
select e.course_id
from enrollment e
where e.student_id = 1001;
```

## EXPLAIN ANALYZE에서 볼 항목

| 항목 | 의미 |
| --- | --- |
| `Execution Time` | 실제 실행 시간 |
| `Planning Time` | 실행 계획 생성 시간 |
| `Seq Scan` | 테이블 전체 스캔 발생 |
| `Index Scan` / `Index Only Scan` | 인덱스 사용 |
| `Rows Removed by Filter` | 조건으로 버려진 row 수 |
| `Buffers: shared hit` | 메모리 버퍼 hit |
| `Buffers: shared read` | 디스크 read 발생 |
| `LockRows` | `FOR UPDATE` 등 row lock 처리 |

부하 테스트 병목을 볼 때는 단일 쿼리의 실행 시간뿐 아니라 같은 쿼리가 얼마나 많이 반복되는지도 함께 확인한다.

## Step 5. Slow SQL 로그 설정 해제

분석이 끝나면 slow SQL 설정을 원래대로 되돌린다.

```bash
docker compose exec -T postgres psql -U user -d enrollment \
  -c "ALTER SYSTEM RESET log_min_duration_statement;"

docker compose exec -T postgres psql -U user -d enrollment \
  -c "SELECT pg_reload_conf();"

docker compose exec -T postgres psql -U user -d enrollment \
  -c "SHOW log_min_duration_statement;"
```

## 참고

Docker로 띄운 PostgreSQL(enrollment 디비)에 1초마다 접속해서, 현재 어떤 작업들이 병목(대기)을 유발하고 있는지 실시간으로 카운트해서 보여달라"는 강력한 DB 트러블슈팅 명령어이다. 보통 DB가 갑자기 느려질 때 원인을 찾기 위해 사용한다.

```bash
watch -n 1 "docker compose exec -T postgres psql -U user -d enrollment -c \"
select state, wait_event_type, wait_event, count(*)
```

현재 baseline 수강신청 경로는 `BaselineEnrollmentService.enroll()`에서 하나의 트랜잭션으로 동작한다.

주요 흐름:

```text
course pessimistic lock 조회
→ 정원 검증
→ 중복 신청/기수강 검증
→ 선수과목 검증
→ 시간표 충돌 검증
→ enrollment 저장
→ course current_count 증가
```

`CourseRepository.findByIdWithPessimisticLock()`은 `@Lock(LockModeType.PESSIMISTIC_WRITE)`를 사용한다. Hotspot 과목 부하에서는 이 쿼리 주변의 row lock 대기와 tail latency 증가를 우선 확인한다.
