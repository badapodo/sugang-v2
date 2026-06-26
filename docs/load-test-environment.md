# Load Test Environment

## 목적

이 단계의 목적은 Spring Boot + PostgreSQL 기반 수강신청 API에 k6 부하를 주입하고, 병목 지표를 관측할 수 있는 환경을 구축하는 것이다.

중요한 점은 성능 개선이 아니라 측정 가능성이다. Baseline의 병목을 캐시나 외부 MQ로 숨기지 않고, 동기식 RDB 구조의 한계를 먼저 관찰한다. 이후 비교 실험군으로 Optimistic Lock과 Single Writer Queue를 같은 payload로 실행한다.

## 구성

```text
k6
  → Spring Boot app
  → PostgreSQL

Spring Boot Actuator → Prometheus
postgres-exporter     → Prometheus
Prometheus            → Grafana
```

Docker Compose 서비스:

| 서비스 | 역할 |
| --- | --- |
| `app` | Baseline, Optimistic, Single Writer 수강신청 API |
| `postgres` | 수강신청 DB |
| `postgres-exporter` | PostgreSQL 지표 수집 |
| `prometheus` | Spring/PostgreSQL/k6 지표 저장 |
| `grafana` | Dashboard 시각화 |
| `k6` | summary JSON 기반 부하 테스트 |
| `k6-prometheus` | Prometheus remote-write 기반 부하 테스트 |

## 제외한 것과 추가 실험군

- Redis
- MQ
- Kafka
- RabbitMQ
- 캐시 기반 정원 차감

Baseline의 목적은 DB row lock, connection pool, SQL/FK/unique constraint 기반 방어가 어떤 병목을 만드는지 보는 것이다.

Single Writer 실험은 외부 MQ 없이 애플리케이션 내부 `BlockingQueue`를 사용한다. 운영용 queue가 아니라, `courseId` 기준 partition과 worker 단일화를 통해 row lock 경합이 줄어드는지 비교하기 위한 로컬 실험군이다.

## 사전 준비

Mock Data Harness 산출물이 있어야 한다.

```text
mock/sugang-mock/output/csv/enrollment_payload.csv
mock/sugang-mock/output/csv/*.csv
```

Mock data는 `.gitignore` 대상이므로 로컬에서 생성하거나 별도로 배치해야 한다.

## 실행 순서

### 1. 애플리케이션 JAR 생성

```bash
./gradlew bootJar
```

### 2. PostgreSQL 실행

```bash
docker compose up -d postgres
```

`spring.jpa.hibernate.ddl-auto=validate` 설정 때문에 schema 생성 전에 app을 먼저 실행하면 애플리케이션이 시작되지 않을 수 있다.

### 3. DB schema 생성 및 Mock Data 적재

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/schema.sql
PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/load.sql
```

반복 테스트 전 초기화:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/reset.sql
```

### 4. App / Observability 스택 실행

```bash
docker compose up --build -d app postgres-exporter prometheus grafana
```

접속 URL:

| 도구 | URL |
| --- | --- |
| Spring Boot | http://localhost:8080 |
| Actuator health | http://localhost:8080/actuator/health |
| Actuator metrics | http://localhost:8080/actuator/metrics |
| Actuator prometheus | http://localhost:8080/actuator/prometheus |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |

Grafana 기본 계정:

```text
admin / admin
```

### 5. summary JSON 기반 k6 실행

```bash
docker compose --profile load run --rm k6
```

결과 파일:

```text
output/k6/baseline-summary.json
```

k6 이미지는 `docker-compose.yml`에서 다음처럼 버전 교체가 가능하다.

```bash
K6_VERSION=latest docker compose --profile load run --rm k6
```

`grafana/k6:0.54.0`에서 `80,000 payload × 200 VU` 초기화 중 `SIGSEGV` 또는 `panic: runtime error`가 발생하면 앱/DB 장애가 아니라 k6 런타임 조합 문제로 본다. 이 경우 k6 이미지를 올리거나, 아래 축소 테스트로 환경 정상성을 먼저 확인한다.

로컬에서 직접 실행할 수도 있다.

```bash
k6 run k6/baseline-enrollment.js \
  --summary-export output/k6/baseline-summary.json
```

NORMAL 100건 스모크 테스트:

```bash
SCENARIO_FILTER=NORMAL LIMIT=100 VUS=1 IGNORE_SCHEDULE=true MAX_DURATION=30s \
docker compose --profile load run --rm k6
```

스모크 테스트는 API/검증 기준 확인이 목적이므로 `scheduled_offset_ms`를 무시하고 즉시 100건을 실행한다.

Burst test:

```bash
SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load run --rm k6
```

Burst test는 `scheduled_offset_ms`를 테스트 시작 기준 절대 오프셋으로 사용한다.

안정성 분리 검증용 축소 테스트:

```bash
SCENARIO_FILTER=ALL ITERATIONS=1000 VUS=10 MAX_DURATION=60s \
docker compose --profile load run --rm k6
```

전체 burst를 끝까지 관측하려면 Baseline row lock 병목 때문에 `MAX_DURATION`을 충분히 길게 둔다.

```bash
K6_VERSION=latest SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=300s \
docker compose --profile load run --rm k6
```

특정 scenario만 실행할 수도 있다.

```bash
SCENARIO_FILTER=PREREQUISITE_FAIL LIMIT=100 VUS=1 \
docker compose --profile load run --rm k6

SCENARIO_FILTER=TIME_CONFLICT LIMIT=100 VUS=1 \
docker compose --profile load run --rm k6
```

### 6. Prometheus remote-write 기반 k6 실행

Prometheus에 k6 지표를 직접 밀어 넣고 Grafana에서 함께 보고 싶을 때 사용한다.

```bash
docker compose --profile load-prometheus run --rm k6-prometheus
```

결과 파일:

```text
output/k6/baseline-summary-prometheus.json
```

Prometheus는 다음 옵션으로 remote write receiver를 활성화한다.

```text
--web.enable-remote-write-receiver
```

`k6-prometheus`는 Prometheus native histogram feature를 켜지 않은 기본 Prometheus와 호환되도록 `K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=false`로 실행한다. 이 값을 `true`로 두면 Prometheus가 `/api/v1/write`에서 `native histograms are disabled` 에러와 함께 500을 반환한다.

## k6 Payload 처리

k6는 Mock Data Harness의 payload를 그대로 읽는다.

```text
mock/sugang-mock/output/csv/enrollment_payload.csv
```

CSV 컬럼:

| 컬럼 | 사용 방식 |
| --- | --- |
| `student_id` | request body `studentId` |
| `course_id` | request body `courseId` |
| `scenario_type` | k6 tag |
| `expected_status` | 응답 검증 기준 |
| `scheduled_offset_ms` | 요청 실행 시점 분산 |

검증 기준:

| expected_status | 기대 결과 |
| --- | --- |
| `200` | 정확히 HTTP 200 |
| `400` | HTTP 4xx 전체를 도메인 실패 정상 응답으로 인정 |

`API_MODE=single-writer`에서는 응답 의미가 다르다. API는 DB 처리 완료가 아니라 queue 접수 결과를 반환한다.

| status | 의미 |
| --- | --- |
| `202` | queue 접수 성공 |
| `429` | queue capacity 초과 |
| `5xx/status 0` | 시스템 실패 |

도메인 실패 payload는 4xx로 방어되면 성공으로 본다. 5xx는 Baseline API 또는 DB 병목이 사용자 오류를 넘어 서버 실패로 전이된 신호다.

`http_req_failed`는 threshold로 사용하지 않는다. k6의 기본 실패 판정은 4xx 도메인 실패까지 실패로 잡을 수 있기 때문이다.

대신 다음 custom metric을 SLO 기준으로 사용한다.

| custom metric | 의미 | threshold |
| --- | --- | --- |
| `baseline_system_failure_rate` | 5xx 또는 네트워크 실패 비율 | `rate < 0.005` |
| `baseline_critical_mismatch_total` | expected 400이 200으로 성공했거나 5xx/네트워크 실패 발생 | `count < 1` |
| `baseline_strict_expected_status_mismatch_total` | expected_status와 실제 응답 분류 불일치 수 | summary에서 원인 확인 |
| `baseline_status_<scenario>_<status>_total` | scenario_type별 HTTP status count | summary에서 분포 확인 |
| `baseline_accepted_total` | baseline/optimistic은 HTTP 200, single-writer는 HTTP 202 count | summary에서 접수량 확인 |
| `baseline_queue_full_total` | single-writer HTTP 429 count | summary에서 queue 포화 확인 |

Mismatch 분류:

| 분류 | 조건 | threshold 여부 |
| --- | --- | --- |
| strict mismatch | `expected_status=200`인데 200이 아니거나, `expected_status=400`인데 4xx가 아닌 경우 | 관측만 수행 |
| critical mismatch | `expected_status=400` 요청이 200으로 성공하거나, 5xx/네트워크 실패가 발생한 경우 | 실패 조건 |

Strict mismatch가 발생하면 summary에 최대 20개 sample을 출력한다.

Sample 필드:

```text
request_id
scenario_type
expected_status
actual_status
student_id
course_id
response_body
```

필터링 환경변수:

| 환경변수 | 예시 | 설명 |
| --- | --- | --- |
| `SCENARIO_FILTER` | `NORMAL` | 특정 scenario만 실행. 쉼표로 복수 지정 가능 |
| `API_MODE` | `baseline` | `baseline`, `optimistic`, `single-writer` endpoint 선택 |
| `BASE_PATH` | `/api/optimistic/enrollments` | `API_MODE` 대신 직접 호출 path를 지정할 때 사용 |
| `LIMIT` | `100` | payload 상위 N건만 실행 |
| `VUS` | `1` | k6 VU 수. 실제 iterations보다 크면 자동으로 iterations 이하로 보정 |
| `ITERATIONS` | `1000` | payload는 전체 로드하되 실행 iteration 수만 제한 |
| `IGNORE_SCHEDULE` | `true` | `true`이면 `scheduled_offset_ms`를 무시하고 즉시 요청 |
| `MAX_DURATION` | `15s` | 최대 실행 시간 |
| `K6_VERSION` | `latest` | `grafana/k6` 이미지 태그. 0.54.0 런타임 crash 회피 시 사용 |
| `EXECUTOR_MODE` | `peak-arrival-rate` | 기본값은 `shared-iterations`. 피크 트래픽 Capacity Planning은 `peak-arrival-rate` 사용 |
| `PEAK_RATE` | `4800` | 피크 구간 초당 요청 유입률 |
| `PEAK_DURATION` | `10s` | 피크 구간 지속 시간 |
| `TAIL_RATE` | `1600` | 후속 구간 초당 요청 유입률 |
| `TAIL_DURATION` | `20s` | 후속 구간 지속 시간 |
| `PRE_ALLOCATED_VUS` | `5000` | arrival-rate executor가 미리 확보할 VU 수 |
| `MAX_VUS` | `30000` | arrival-rate executor가 확장할 수 있는 최대 VU 수 |
| `SINGLE_WRITER_PARTITION_COUNT` | `8` | app 컨테이너의 courseId partition 수 |
| `SINGLE_WRITER_QUEUE_CAPACITY_PER_PARTITION` | `10000` | app 컨테이너의 partition별 queue capacity |

### 피크 타임 Capacity Planning 테스트

전체 80,000건 중 60%인 48,000건을 첫 10초에, 나머지 32,000건을 이후 20초에 주입한다.

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

`peak-arrival-rate` 모드는 k6 `constant-arrival-rate` executor를 사용한다. 이 모드에서는 `scheduled_offset_ms`를 무시하고 k6 arrival scheduler가 요청 유입 시점을 통제한다.

성공 기준:

| 항목 | 기준 |
| --- | --- |
| `dropped_iterations` | 0 |
| `baseline_critical_mismatch_total` | 0 |
| `baseline_system_failure_rate` | `< 0.005` |
| `http_req_duration p(99)` | `< 5000ms` |

### Baseline / Optimistic Lock / Single Writer 비교 테스트

기존 baseline endpoint는 그대로 유지한다.

```text
POST /api/baseline/enrollments
```

Optimistic Lock 실험 endpoint는 별도로 분리한다.

```text
POST /api/optimistic/enrollments
```

Single Writer 실험 endpoint도 별도로 분리한다.

```text
POST /api/single-writer/enrollments
```

k6는 같은 payload를 사용하고 `API_MODE`만 바꿔 세 endpoint를 비교한다.

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
```

Optimistic Lock 충돌은 재시도 없이 409 Conflict로 응답한다. 응답 body의 `reason`은 발생한 예외 타입에 따라 `ObjectOptimisticLockingFailureException` 또는 `OptimisticLockException`이 된다.

Single Writer는 enqueue 성공 시 202 Accepted를 반환한다. worker 처리 결과는 즉시 API 응답으로 전달하지 않고 Prometheus metric과 로그로 관측한다.

Endpoint mapping:

| API_MODE | endpoint |
| --- | --- |
| `baseline` | `/api/baseline/enrollments` |
| `optimistic` | `/api/optimistic/enrollments` |
| `single-writer` | `/api/single-writer/enrollments` |

## 수집 지표

### Spring Boot / Actuator

| 지표 | 의미 |
| --- | --- |
| `http_server_requests_seconds_count` | API request count/rate |
| `http_server_requests_seconds_bucket` | P95/P99 latency 계산 |
| `http_server_requests_seconds_sum` | 평균 latency 계산 |
| `hikaricp_connections_active` | 사용 중인 DB connection |
| `hikaricp_connections_pending` | connection 대기 thread |
| `hikaricp_connections_timeout_total` | connection timeout |
| `jvm_memory_used_bytes` | JVM memory 사용량 |

### Single Writer / Actuator

| 지표 | 의미 |
| --- | --- |
| `single_writer_enqueued_count_total` | queue enqueue 성공 수 |
| `single_writer_rejected_count_total` | queue capacity 초과로 거절된 수 |
| `single_writer_processed_count_total` | worker DB 처리 성공 수 |
| `single_writer_failed_count_total` | worker DB 처리 실패 수 |
| `single_writer_queue_depth` | 전체 partition queue depth 합계 |
| `single_writer_partition_queue_depth{partition="n"}` | partition별 queue depth |
| `single_writer_processing_latency_seconds` | worker가 command 하나를 DB 처리하는 시간 |
| `single_writer_end_to_end_latency_seconds` | enqueue부터 worker 처리 완료까지 걸린 시간 |
| `single_writer_domain_failure_count_total{reason="..."}` | worker 도메인 실패 또는 queue full 사유별 count |

### PostgreSQL / postgres-exporter

| 지표 | 의미 |
| --- | --- |
| `pg_stat_activity_count` | DB connection/session 상태 |
| `pg_stat_activity_wait_count` | custom query 기반 wait event 상태. `wait_event_type="Lock"`으로 lock wait 세션 수 확인 |
| `pg_stat_database_deadlocks` | deadlock 발생 수 |
| `pg_locks_count` | lock 상태 |

### k6

| 지표 | 의미 |
| --- | --- |
| `http_reqs` | 요청 수/TPS |
| `http_req_duration` | end-to-end latency |
| `baseline_system_failure_rate` | 5xx 또는 네트워크 실패 비율 |
| `baseline_system_failure_total` | 5xx 또는 네트워크 실패 수 |
| `baseline_strict_expected_status_mismatch_total` | expected_status와 실제 status 불일치 수 |
| `baseline_critical_mismatch_total` | 반드시 0이어야 하는 critical mismatch 수 |
| `baseline_scenario_requests_total` | scenario_type별 요청 수 |
| `baseline_status_<scenario>_<status>_total` | scenario_type + HTTP status별 응답 수 |
| `baseline_accepted_total` | 접수 성공 수. single-writer에서는 202 count |
| `baseline_queue_full_total` | single-writer 429 count |

## Grafana Dashboard

자동 provisioning 대상:

```text
infra/grafana/provisioning/datasources/prometheus.yml
infra/grafana/provisioning/dashboards/baseline.yml
infra/grafana/dashboards/baseline-dashboard.json
```

Dashboard 이름:

```text
Sugang Baseline Load Test
```

패널 구성:

| 패널 | 목적 |
| --- | --- |
| Request rate | Spring/k6 기준 요청 처리량 확인 |
| P95/P99 latency | Hotspot 요청에서 tail latency가 증가하는지 확인 |
| HTTP status ratio | 2xx/4xx/5xx 비율 확인 |
| HikariCP active/pending | connection pool 포화 여부 확인 |
| PostgreSQL connections | DB session 상태 확인 |
| PostgreSQL lock wait / deadlock | row lock 경합과 deadlock 발생 여부 확인 |

`postgres-exporter` 기본 `pg_stat_activity_count` metric에는 `wait_event_type` 라벨이 없다. 따라서 lock wait 세션 수는 custom query로 추가한 `pg_stat_activity_wait_count`를 사용한다.

```promql
sum(pg_stat_activity_wait_count{job="postgres-exporter", datname="enrollment", wait_event_type="Lock"}) or vector(0)
```

## 해석 기준

| 현상 | 해석 |
| --- | --- |
| P95/P99 급증 | Hotspot row lock 또는 DB connection 병목 가능성 |
| Hikari pending 증가 | connection pool 부족 또는 DB 응답 지연 |
| 5xx 증가 | 도메인 실패가 서버 실패로 전이됨 |
| PostgreSQL lock wait 증가 | `SELECT ... FOR UPDATE` 경합 발생 |
| deadlock 증가 | 트랜잭션 순서 또는 lock 범위 재검토 필요 |

## 이번 단계에서 Redis/MQ를 제외한 이유

Redis, MQ, Kafka, 비동기 큐는 병목을 완화하거나 요청을 흡수하는 장치다. Baseline 단계에서 이를 먼저 넣으면 동기식 RDB 구조의 실제 한계를 관측하기 어렵다.

따라서 이번 단계는 다음 질문에만 집중한다.

```text
Spring Boot + PostgreSQL + DB transaction + row lock만으로
수강신청 Hotspot 트래픽을 어디까지 방어할 수 있는가?
```

이 결과가 있어야 다음 단계에서 Redis Lock, Redis Queue, MQ 기반 비동기 대기열 도입의 효과를 비교할 수 있다.
