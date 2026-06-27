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
→ Spring Boot /api/baseline/enrollments, /api/optimistic/enrollments, /api/single-writer/enrollments, /api/single-writer-sync/enrollments, /api/in-memory-single-writer/enrollments
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
| `docs/postgres-slow-query-analysis.md` | 부하 테스트 중 PostgreSQL slow SQL 수집 및 EXPLAIN ANALYZE 절차 |
| `docs/peak-traffic-capacity-test.md` | 초반 10초 60% 집중 유입을 검증하는 constant-arrival-rate 기반 피크 트래픽 테스트 |

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
