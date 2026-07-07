# sugang-v2

동기식 RDB 락 기반 수강신청 API Baseline 프로젝트.

이 프로젝트는 `tmp/sugang`의 기존 구현에서 Baseline에 필요한 JPA 엔티티/Repository만 가져와 별도 프로젝트로 정리한 버전이다. Redis, MQ, JWT 인증은 의도적으로 제외했다.

## 목표

Mock Data Harness가 만든 8만 건 수강신청 payload를 사용해 다음을 측정한다.

- Hotspot 과목에서 PostgreSQL row lock 경합이 어떻게 발생하는가
- 동기식 WAS-RDB 구조의 P95/P99와 TPS 한계는 어디인가
- 정원 초과, 중복 신청, 선수과목, 시간표 충돌이 DB 트랜잭션 안에서 방어되는가

## 구성

```text
k6
→ Spring Boot enrollment APIs (baseline, optimistic, partition single-writer, global in-memory single-writer)
→ JPA @Transactional
→ PostgreSQL PESSIMISTIC_WRITE 또는 OPTIMISTIC lock
```

## 주요 파일

| 파일 | 설명 |
| --- | --- |
| `src/main/java/badapodo/sugang/service/BaselineEnrollmentService.java` | 동기식 RDB Baseline 수강신청 로직 |
| `src/main/java/badapodo/sugang/service/OptimisticEnrollmentService.java` | 낙관적 락 기반 수강신청 실험 로직 |
| `src/main/java/badapodo/sugang/service/singlewriter/SingleWriterEnrollmentQueueService.java` | courseId partition 기반 Single Writer Queue 실험 로직 |
| `src/main/java/badapodo/sugang/service/inmemory/InMemorySingleWriterEnrollmentService.java` | 메모리 CourseState 기반 Single Writer 실험 로직 |
| `src/main/java/badapodo/sugang/service/inmemory/InMemoryEnrollmentWriteBehindService.java` | In-Memory Single Writer 성공 이벤트 비동기 DB insert |
| `src/main/java/badapodo/sugang/controller/BaselineEnrollmentController.java` | 인증 없는 Baseline API |
| `src/main/java/badapodo/sugang/controller/OptimisticEnrollmentController.java` | 인증 없는 Optimistic Lock 실험 API |
| `src/main/java/badapodo/sugang/controller/SingleWriterEnrollmentController.java` | 인증 없는 Single Writer Queue 실험 API |
| `src/main/java/badapodo/sugang/controller/SingleWriterSyncEnrollmentController.java` | worker 처리 결과를 기다리는 동기 응답형 Single Writer API |
| `src/main/java/badapodo/sugang/controller/InMemorySingleWriterEnrollmentController.java` | 메모리 상태를 즉시 갱신하고 최종 결과를 반환하는 Single Writer API |
| `infra/postgres/schema.sql` | 테이블, FK, unique constraint, index 생성 |
| `infra/postgres/load.sql` | Mock CSV COPY 적재 |
| `infra/postgres/reset.sql` | 반복 테스트용 enrollment/current_count 초기화 |
| `k6/baseline-enrollment.js` | `enrollment_payload.csv` 기반 부하 테스트 |
| `docs/baseline-architecture.md` | Baseline 목적/구조/측정 지표 |
| `docs/schema-gap-analysis.md` | 기존 엔티티와 Mock CSV 매핑 분석 |
| `docs/api-spec.md` | Baseline API 요청/응답 명세 |
| `docs/key-code.md` | 주요 코드 흐름과 책임 정리 |
| `docs/load-test-environment.md` | k6, Prometheus, Grafana 기반 부하 테스트 환경 |
| `docs/grafana-metrics-guide.md` | Grafana 24개 패널의 지표 의미와 병목 판정 기준 |
| `docs/postgres-slow-query-analysis.md` | 부하 테스트 중 PostgreSQL slow SQL 수집 및 EXPLAIN ANALYZE 절차 |
| `docs/peak-traffic-capacity-test.md` | 초반 10초 60% 집중 유입을 검증하는 arrival-rate 기반 피크 트래픽 테스트 |

## 실행

PostgreSQL 실행:

```bash
docker compose up -d postgres
```

스키마 생성 및 Mock Data 적재:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/schema.sql
PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/load.sql
```

애플리케이션 실행:

```bash
docker compose up --build -d app postgres-exporter prometheus grafana
```

summary JSON 기반 k6 실행:

```bash
docker compose --profile load run --rm k6
```

Prometheus remote-write 기반 k6 실행:

```bash
docker compose --profile load-prometheus run --rm k6-prometheus
```

`k6-prometheus`는 Prometheus native histogram feature를 켜지 않은 기본 Prometheus와 호환되도록 `K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=false`로 실행한다. 이 값을 `true`로 두면 Prometheus가 `/api/v1/write`에서 `native histograms are disabled` 에러와 함께 500을 반환한다.

> k6 이미지는 `K6_VERSION`으로 교체할 수 있다. `grafana/k6:0.54.0`에서 80,000 payload × 200 VU 초기화 중 `SIGSEGV`/panic이 발생하면 최신 이미지로 실행한다.
>
> ```bash
> K6_VERSION=latest docker compose --profile load run --rm k6
> ```

NORMAL 100건 스모크 테스트:

```bash
SCENARIO_FILTER=NORMAL LIMIT=100 VUS=1 IGNORE_SCHEDULE=true MAX_DURATION=30s \
docker compose --profile load run --rm k6
```

Optimistic Lock endpoint 스모크 테스트:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

API_MODE=optimistic SCENARIO_FILTER=NORMAL LIMIT=100 VUS=1 IGNORE_SCHEDULE=true MAX_DURATION=30s \
docker compose --profile load run --rm k6
```

Single Writer endpoint 스모크 테스트:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

API_MODE=single-writer SCENARIO_FILTER=NORMAL LIMIT=100 VUS=1 IGNORE_SCHEDULE=true MAX_DURATION=30s \
docker compose --profile load run --rm k6
```

Single Writer Sync endpoint 스모크 테스트:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

API_MODE=single-writer-sync SCENARIO_FILTER=NORMAL LIMIT=100 VUS=1 IGNORE_SCHEDULE=true MAX_DURATION=30s \
docker compose --profile load run --rm k6
```

In-Memory Single Writer endpoint 스모크 테스트:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

docker compose up --build -d app

API_MODE=in-memory-single-writer SCENARIO_FILTER=NORMAL LIMIT=100 VUS=1 IGNORE_SCHEDULE=true MAX_DURATION=30s \
docker compose --profile load run --rm k6
```

같은 payload/조건으로 Baseline과 Optimistic 비교:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

API_MODE=baseline SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load-prometheus run --rm k6-prometheus

PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

API_MODE=optimistic SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load-prometheus run --rm k6-prometheus
```

같은 payload/조건으로 Baseline, Optimistic, Single Writer 비교:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

API_MODE=baseline SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load-prometheus run --rm k6-prometheus

PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

API_MODE=optimistic SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load-prometheus run --rm k6-prometheus

PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

API_MODE=single-writer SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load-prometheus run --rm k6-prometheus

PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

API_MODE=single-writer-sync SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load-prometheus run --rm k6-prometheus

PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

docker compose up --build -d app

API_MODE=in-memory-single-writer SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load-prometheus run --rm k6-prometheus
```

Prometheus remote-write smoke test:

```bash
SCENARIO_FILTER=NORMAL LIMIT=100 VUS=1 IGNORE_SCHEDULE=true MAX_DURATION=30s \
docker compose --profile load-prometheus run --rm k6-prometheus
```

스케줄을 켠 상태의 burst test:

```bash
SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load run --rm k6
```

Grafana에서 k6 지표까지 함께 볼 때:

```bash
SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
API_MODE=in-memory-single-writer \
docker compose --profile load-prometheus run --rm k6-prometheus
```

피크 타임 Capacity Planning 테스트(optimistic):

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

EXECUTOR_MODE=peak-arrival-rate \
API_MODE=optimistic \
SCENARIO_FILTER=ALL \
PEAK_RATE=4800 PEAK_DURATION=10s \
TAIL_RATE=1600 TAIL_DURATION=20s \
PRE_ALLOCATED_VUS=5000 MAX_VUS=30000 \
docker compose --profile load-prometheus run --rm k6-prometheus
```

피크 타임 Capacity Planning 테스트(baseline):

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

EXECUTOR_MODE=peak-arrival-rate \
SCENARIO_FILTER=ALL \
PEAK_RATE=4800 PEAK_DURATION=10s \
TAIL_RATE=1600 TAIL_DURATION=20s \
PRE_ALLOCATED_VUS=5000 MAX_VUS=30000 \
docker compose --profile load-prometheus run --rm k6-prometheus
```

피크 타임 Capacity Planning 테스트(single-writer):

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

EXECUTOR_MODE=peak-arrival-rate \
API_MODE=single-writer \
SCENARIO_FILTER=ALL \
PEAK_RATE=4800 PEAK_DURATION=10s \
TAIL_RATE=1600 TAIL_DURATION=20s \
PRE_ALLOCATED_VUS=5000 MAX_VUS=30000 \
docker compose --profile load-prometheus run --rm k6-prometheus
```

피크 타임 Capacity Planning 테스트(single-writer-sync):

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

EXECUTOR_MODE=peak-arrival-rate \
API_MODE=single-writer-sync \
SCENARIO_FILTER=ALL \
PEAK_RATE=4800 PEAK_DURATION=10s \
TAIL_RATE=1600 TAIL_DURATION=20s \
PRE_ALLOCATED_VUS=5000 MAX_VUS=30000 \
docker compose --profile load-prometheus run --rm k6-prometheus
```

피크 타임 Capacity Planning 테스트(in-memory-single-writer):

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

docker compose up --build -d app

EXECUTOR_MODE=peak-arrival-rate \
API_MODE=in-memory-single-writer \
SCENARIO_FILTER=ALL \
PEAK_RATE=4800 PEAK_DURATION=10s \
TAIL_RATE=1600 TAIL_DURATION=20s \
PRE_ALLOCATED_VUS=5000 MAX_VUS=30000 \
docker compose --profile load-prometheus run --rm k6-prometheus
```

피크 타임 Capacity Planning 테스트(global-in-memory-single-writer):

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql
docker compose restart app

EXECUTOR_MODE=peak-arrival-rate \
API_MODE=global-in-memory-single-writer \
SCENARIO_FILTER=ALL \
PEAK_RATE=4800 PEAK_DURATION=10s \
TAIL_RATE=1600 TAIL_DURATION=20s \
PRE_ALLOCATED_VUS=5000 MAX_VUS=30000 \
docker compose --profile load-prometheus run --rm k6-prometheus
```

이 테스트는 80,000건 중 48,000건을 첫 10초에, 32,000건을 이후 20초에 주입한다. `dropped_iterations=0`, `baseline_critical_mismatch_total=0`, `baseline_system_failure_rate<0.005`, `p99<5000ms`를 기준으로 본다.

`grafana/k6:0.54.0`에서 200 VU 전체 burst가 k6 런타임 내부 crash를 일으키는 환경이면 다음 순서로 확인한다.

```bash
# 1. 앱/DB/API 검증용 smoke
SCENARIO_FILTER=NORMAL LIMIT=100 VUS=1 IGNORE_SCHEDULE=true MAX_DURATION=30s \
docker compose --profile load run --rm k6

# 2. 전체 payload 로드 + 축소 실행 검증
SCENARIO_FILTER=ALL ITERATIONS=1000 VUS=10 MAX_DURATION=60s \
docker compose --profile load run --rm k6

# 3. k6 이미지 업그레이드 후 burst 재시도
K6_VERSION=latest SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=300s \
docker compose --profile load run --rm k6
```

반복 테스트 초기화:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/reset.sql
```

## API

Baseline:

```http
POST /api/baseline/enrollments
Content-Type: application/json

{
  "studentId": 1001,
  "courseId": 20
}
```

Optimistic Lock 실험:

```http
POST /api/optimistic/enrollments
Content-Type: application/json

{
  "studentId": 1001,
  "courseId": 20
}
```

Single Writer Queue 실험:

```http
POST /api/single-writer/enrollments
Content-Type: application/json

{
  "studentId": 1001,
  "courseId": 20
}
```

접수 성공:

```http
202 Accepted
```

```json
{
  "status": "ACCEPTED",
  "commandId": "uuid",
  "partitionIndex": 4
}
```

Queue capacity 초과:

```http
429 Too Many Requests
```

```json
{
  "status": "FAIL",
  "reason": "QueueFullException",
  "message": "수강신청 요청이 일시적으로 많아 접수하지 못했습니다."
}
```

Single Writer Sync 실험:

```http
POST /api/single-writer-sync/enrollments
Content-Type: application/json

{
  "studentId": 1001,
  "courseId": 20
}
```

동작 방식:

- 요청을 `courseId` 기반 partition queue에 넣는다.
- API thread는 worker가 DB 검증/저장을 끝낼 때까지 대기한다.
- worker 결과에 따라 baseline과 같은 의미의 `200`, `400`, `409`, `500` 응답을 반환한다.
- `SINGLE_WRITER_RESPONSE_TIMEOUT_MS`를 넘기면 `504 Gateway Timeout`을 반환한다.
- queue capacity 초과는 `429 Too Many Requests`를 반환한다.

In-Memory Single Writer 실험:

```http
POST /api/in-memory-single-writer/enrollments
Content-Type: application/json

{
  "studentId": 1001,
  "courseId": 20
}
```

동작 방식:

- 애플리케이션 시작 시 `Course`와 기존 `Enrollment`를 읽어 courseId별 `CourseState`를 메모리에 만든다.
- `CourseState`는 `capacity`, `currentCount`, `remainingCapacity`, `enrolledStudentIds`를 가진다.
- HTTP thread는 `CourseState`를 직접 수정하지 않고 command를 partition queue에 넣은 뒤 결과 future를 기다린다.
- 같은 `courseId`는 항상 같은 partition worker에서 FIFO로 처리된다.
- worker는 메모리 상태에서 선수과목, 중복 신청, 시간표 충돌, 정원 초과를 검증하고 즉시 `200`, `400`, `409` 결과를 만든다.
- 성공 이벤트는 write-behind queue에 넣고, 별도 worker가 `Enrollment`를 비동기로 insert한다.

운영상 한계:

- WAL/Event Log가 없으므로 프로세스 장애 시 아직 DB에 쓰이지 않은 성공 이벤트가 유실될 수 있다.
- 메모리 상태는 단일 app 인스턴스 안에서만 일관되며, 다중 인스턴스 확장은 지원하지 않는다.
- 선수과목/시간표 충돌 검증은 애플리케이션 시작 시 로드한 메모리 snapshot과 실행 중 성공한 in-memory 신청 상태를 기준으로 수행한다.
- `course.current_count`는 write-behind에서 갱신하지 않고 `enrollment` insert만 수행한다. 재시작 시 기존 `enrollment`와 `course.current_count`를 함께 읽어 메모리 상태를 재구성한다.
- DB write-behind 실패는 HTTP 성공 응답 이후 발생할 수 있으므로 `in_memory_single_writer.write_behind.failed.count`를 반드시 확인해야 한다.

Global In-Memory Single Writer 실험:

```http
POST /api/global-in-memory-single-writer/enrollments
Content-Type: application/json

{
  "studentId": 1001,
  "courseId": 20
}
```

동작 방식:

- 모든 command를 하나의 bounded queue에 넣고 단 하나의 global writer가 FIFO로 처리한다.
- writer만 course remaining capacity, student enrollment set, student timetable을 검증하고 수정한다.
- 검증 순서는 선수과목, 중복 신청, 시간표 충돌, 정원 초과 순서다.
- HTTP thread는 future를 기다리고 최종 결과를 `200`, `400`, `409`로 받는다. queue full은 `429`, timeout은 `504`다.
- 성공 이벤트만 write-behind queue로 전달되며, DB insert는 설정된 worker pool에서 비동기로 처리한다.
- course partition 간 학생 상태 race가 없으므로 `in-memory-single-writer`와 student time conflict 정합성을 비교할 수 있다.

운영상 한계:

- WAL/Event Log가 없으므로 HTTP `200` 이후 DB 저장 전에 프로세스가 종료되면 성공 이벤트가 유실될 수 있다.
- DB insert 실패는 이미 반환된 HTTP 성공과 메모리 상태를 롤백하지 않는다. `global_single_writer.write_behind.failed.count`를 반드시 관측해야 한다.
- 메모리 상태는 한 app 인스턴스에만 존재하므로 다중 인스턴스에서 global ordering을 보장하지 않는다.
- 응답 timeout 이후에도 queue에 들어간 command는 writer가 나중에 처리할 수 있다.
- 단일 writer의 처리량이 전체 matching 처리량 상한이며, write-behind worker 수를 늘려도 메모리 판정은 병렬화되지 않는다.

Global In-Memory Single Writer Async Web 실험:

```http
POST /api/global-in-memory-single-writer-async-web/enrollments
Content-Type: application/json

{
  "studentId": 1001,
  "courseId": 20
}
```

두 endpoint는 같은 global queue, writer, in-memory state와 write-behind 경로를 사용하며 최종 응답 의미도 같다.

| endpoint | HTTP 대기 방식 | 최종 응답 |
| --- | --- | --- |
| `/api/global-in-memory-single-writer/enrollments` | controller thread가 `CompletableFuture.get(timeout)`으로 대기 | `200`, `400`, `409`, `429`, `504` |
| `/api/global-in-memory-single-writer-async-web/enrollments` | `DeferredResult`를 반환하고 future callback이 응답을 완료 | `200`, `400`, `409`, `429`, `504` |

Async Web 방식에서도 클라이언트는 writer의 최종 판정을 기다린다. 차이는 대기 중 Tomcat worker thread를 점유하지 않는다는 점이다. timeout과 writer 완료가 경합하면 먼저 완료된 응답만 반환하며, timeout 이후 늦게 완료된 future가 중복 응답을 만들지 않는다.

피크 비교 실행:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

docker compose restart app

EXECUTOR_MODE=peak-arrival-rate \
API_MODE=global-in-memory-single-writer-async-web \
SCENARIO_FILTER=ALL \
PEAK_RATE=4800 PEAK_DURATION=10s \
TAIL_RATE=1600 TAIL_DURATION=20s \
PRE_ALLOCATED_VUS=5000 MAX_VUS=30000 \
docker compose --profile load-prometheus run --rm k6-prometheus
```

별도 Micrometer 지표:

- `global_single_writer_async_web.enqueued.count`
- `global_single_writer_async_web.timeout.count`
- `global_single_writer_async_web.inflight.count`
- `global_single_writer_async_web.response.latency`

운영상 내구성 한계와 timeout 이후 command 처리 가능성은 기존 Global In-Memory Single Writer와 동일하다.

Global In-Memory Single Writer Fast 실험:

```http
POST /api/global-in-memory-single-writer-fast/enrollments?studentId=1001&courseId=20
X-Scenario-Type: NORMAL
```

이 endpoint는 운영 API가 아니라 Spring MVC/Jackson overhead를 분리 측정하기 위한 실험용 endpoint다.

- JSON `@RequestBody`를 사용하지 않고 `studentId`, `courseId`를 request parameter로 받는다.
- 기존 `GlobalInMemorySingleWriterEnrollmentService`와 동기 `CompletableFuture.get(timeout)` 경로를 그대로 사용한다.
- 성공 `200`, 도메인 실패 `400/409`, queue full `429`, timeout `504` 정책이 기존 global endpoint와 같다.
- `DispatcherServlet`과 `RequestMappingHandlerAdapter`는 여전히 사용한다.
- 제거되는 비교 대상은 `RequestResponseBodyMethodProcessor.readWithMessageConverters`와 Jackson JSON 역직렬화 경로다.

```bash
EXECUTOR_MODE=peak-arrival-rate \
API_MODE=global-in-memory-single-writer-fast \
SCENARIO_FILTER=ALL \
PEAK_RATE=4800 PEAK_DURATION=10s \
TAIL_RATE=1600 TAIL_DURATION=20s \
PRE_ALLOCATED_VUS=5000 MAX_VUS=30000 \
docker compose --profile load-prometheus run --rm k6-prometheus
```

Global write-behind 저장 모드:

| 모드 | 설정 | 저장 방식 |
| --- | --- | --- |
| Batch JDBC (기본값) | `WRITE_BEHIND_MODE=batch-jdbc` | 최대 N개 event를 모아 하나의 transaction과 `JdbcTemplate.batchUpdate`로 저장 |
| Single JPA (비교용) | `WRITE_BEHIND_MODE=single-jpa` | event마다 별도 transaction, 중복 조회, JPA `saveAndFlush` 실행 |

Batch JDBC는 `enrollment.student_id`, `course_id`, `created_date`, `last_modified_date`를 insert한다. `id`는 PostgreSQL identity가 생성하며 audit user 컬럼은 기존 JPA 경로와 마찬가지로 값이 없으면 `NULL`이다. `(student_id, course_id)` 충돌은 `ON CONFLICT DO NOTHING`으로 처리하고 기존 duplicate/failure metric에 반영한다.

```bash
# 기본 batch 모드
WRITE_BEHIND_MODE=batch-jdbc \
WRITE_BEHIND_BATCH_SIZE=500 \
WRITE_BEHIND_BATCH_WAIT_MS=10 \
docker compose up --build -d app

# 기존 단건 JPA 비교 모드
WRITE_BEHIND_MODE=single-jpa \
docker compose up --build -d app
```

Batch 전용 Micrometer metric:

- `write_behind.batch.size`: 실제 저장 batch의 event 수
- `write_behind.batch.success.count`: transaction이 성공한 batch 수
- `write_behind.batch.failed.count`: transaction이 실패한 batch 수
- `write_behind.batch.latency`: batch transaction 처리 시간

Batch mode에서도 HTTP 응답, global writer 판정, write-behind queue와 기존 event 단위 success/failure/duplicate metric 의미는 유지된다.

### JVM PID 확인
```bash
docker exec -it sugang-v2-baseline-app jcmd
```
### JFR 시작
```bash
docker exec -it sugang-v2-baseline-app jcmd 1 JFR.start \
  name=global-writer-profile \
  settings=profile \
  delay=5s \
  duration=60s \
  filename=/tmp/global-writer-profile.jfr
```

### 복사
```bash
docker cp sugang-v2-baseline-app:/tmp/global-writer-profile.jfr ./global-writer-profile.jfr
```

Single Writer 설정값:

| 환경변수 | 기본값 | 설명 |
| --- | ---: | --- |
| `SINGLE_WRITER_PARTITION_COUNT` | `8` | courseId hash 기반 partition 수 |
| `SINGLE_WRITER_QUEUE_CAPACITY_PER_PARTITION` | `10000` | partition별 queue capacity |
| `SINGLE_WRITER_RESPONSE_TIMEOUT_MS` | `5000` | single-writer-sync API thread가 worker 결과를 기다리는 최대 시간 |
| `IN_MEMORY_SINGLE_WRITER_PARTITION_COUNT` | `8` | in-memory courseId hash 기반 partition 수 |
| `IN_MEMORY_SINGLE_WRITER_QUEUE_CAPACITY_PER_PARTITION` | `10000` | in-memory partition별 queue capacity |
| `IN_MEMORY_SINGLE_WRITER_RESPONSE_TIMEOUT_MS` | `5000` | in-memory API thread가 worker 결과를 기다리는 최대 시간 |
| `IN_MEMORY_SINGLE_WRITER_WRITE_BEHIND_QUEUE_CAPACITY` | `100000` | 성공 이벤트 DB insert 대기열 capacity |
| `GLOBAL_SINGLE_WRITER_QUEUE_CAPACITY` | `100000` | global command queue와 write-behind queue의 capacity |
| `GLOBAL_SINGLE_WRITER_RESPONSE_TIMEOUT_MS` | `5000` | global writer 결과를 기다리는 최대 시간 |
| `GLOBAL_SINGLE_WRITER_WRITE_BEHIND_WORKER_COUNT` | `4` | 비동기 DB insert worker 수 |
| `WRITE_BEHIND_MODE` | `batch-jdbc` | `batch-jdbc` 또는 기존 비교용 `single-jpa` |
| `WRITE_BEHIND_BATCH_SIZE` | `500` | 한 transaction에 저장할 최대 event 수 |
| `WRITE_BEHIND_BATCH_WAIT_MS` | `10` | 첫 event 이후 batch를 모으는 최대 대기 시간(ms) |

성공:

```json
{
  "status": "SUCCESS"
}
```

실패:

```json
{
  "status": "FAIL",
  "reason": "DuplicateEnrollmentException",
  "message": "현재 학기에 이미 신청한 과목입니다."
}
```

## Observability

Actuator/Prometheus endpoint:

```text
http://localhost:8080/actuator/prometheus
```

Docker Compose 전체 실행 시:

```bash
./gradlew bootJar
docker compose up --build
```

- App: http://localhost:8080
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000

## Baseline Resource Profile

Docker Compose 기준 app 컨테이너 리소스와 DB connection pool은 다음 값으로 고정한다.

| 항목 | 값 |
| --- | --- |
| CPU | 8 vCPU |
| RAM | 32GB |
| HikariCP maximum pool size | 16 |
