# Load Test Implementation Summary

이 문서는 현재 프로젝트 파일에 존재하는 부하 테스트 환경 구현을 사실 기준으로 정리한다. 블로그용 서술이 아니라, 코드와 설정 파일 조사 결과를 기록한다.

## 1. 부하 테스트 환경의 실제 구성 요소

조사 기준 파일:

- `docker-compose.yml`
- `src/main/resources/application.yml`
- `build.gradle`
- `infra/prometheus/prometheus.yml`
- `infra/grafana/provisioning/datasources/prometheus.yml`
- `infra/grafana/provisioning/dashboards/baseline.yml`
- `infra/grafana/dashboards/baseline-dashboard.json`
- `k6/baseline-enrollment.js`

### 서비스 구성

| 구성 요소 | 실제 구현 |
| --- | --- |
| `app` | Spring Boot baseline 수강신청 API. Docker build 대상은 현재 프로젝트 루트. 컨테이너명은 `sugang-v2-baseline-app`. |
| `postgres` | `postgres:16` 이미지 사용. 컨테이너명은 `sugang-v2-baseline-db`. |
| `postgres-exporter` | 사용 중. `prometheuscommunity/postgres-exporter:v0.15.0` 이미지 사용. 컨테이너명은 `sugang-v2-baseline-postgres-exporter`. |
| `prometheus` | `prom/prometheus:v2.54.1` 이미지 사용. Spring Actuator와 postgres-exporter scrape. remote-write receiver 옵션 활성화. |
| `grafana` | `grafana/grafana:11.1.4` 이미지 사용. Prometheus datasource와 dashboard file provisioning 설정. |
| `k6` | summary JSON 기반 실행 서비스. `load` profile에서만 실행. |
| `k6-prometheus` | Prometheus remote-write 기반 실행 서비스. `load-prometheus` profile에서만 실행. |

기타 Redis, MQ, Kafka, RabbitMQ, 비동기 큐 서비스는 `docker-compose.yml`에 없다.

## 2. Docker Compose 실행 방식

### 기본 서비스 실행

README와 docs에 있는 실행 명령:

```bash
docker compose up -d postgres

PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/schema.sql
PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/load.sql

docker compose up --build -d app postgres-exporter prometheus grafana
```

전체 실행 명령:

```bash
./gradlew bootJar
docker compose up --build
```

반복 테스트 초기화:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/reset.sql
```

`infra/postgres/reset.sql`은 `enrollment`를 truncate하고 `course.current_count`, `course.version`을 0으로 갱신한다.

### k6 profile

| profile | 서비스 | 실행 명령 | 결과 파일 |
| --- | --- | --- | --- |
| `load` | `k6` | `docker compose --profile load run --rm k6` | `output/k6/baseline-summary.json` |
| `load-prometheus` | `k6-prometheus` | `docker compose --profile load-prometheus run --rm k6-prometheus` | `output/k6/baseline-summary-prometheus.json` |

`k6`와 `k6-prometheus` 모두 `grafana/k6:${K6_VERSION:-latest}` 이미지를 사용한다. 즉, 현재 compose 기준 기본 k6 태그는 `latest`이다.

### 주요 실행 명령

NORMAL 100건 smoke:

```bash
SCENARIO_FILTER=NORMAL LIMIT=100 VUS=1 IGNORE_SCHEDULE=true MAX_DURATION=30s \
docker compose --profile load run --rm k6
```

스케줄 기반 burst:

```bash
SCENARIO_FILTER=ALL VUS=200 MAX_DURATION=90s \
docker compose --profile load run --rm k6
```

전체 payload 로드 + 축소 실행:

```bash
SCENARIO_FILTER=ALL ITERATIONS=1000 VUS=10 MAX_DURATION=60s \
docker compose --profile load run --rm k6
```

k6 이미지 태그 교체:

```bash
K6_VERSION=latest docker compose --profile load run --rm k6
```

### 환경변수

#### app

| 변수 | 값 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/enrollment` |
| `SPRING_DATASOURCE_USERNAME` | `user` |
| `SPRING_DATASOURCE_PASSWORD` | `password` |
| `HIKARI_MAXIMUM_POOL_SIZE` | `16` |
| `HIKARI_CONNECTION_TIMEOUT_MS` | `8000` |

app container resource:

| 항목 | 값 |
| --- | --- |
| CPU | `8.0` |
| memory | `32g` |

#### postgres

| 변수 | 값 |
| --- | --- |
| `POSTGRES_USER` | `user` |
| `POSTGRES_PASSWORD` | `password` |
| `POSTGRES_DB` | `enrollment` |

#### postgres-exporter

| 변수 | 값 |
| --- | --- |
| `DATA_SOURCE_NAME` | `postgresql://user:password@postgres:5432/enrollment?sslmode=disable` |

#### grafana

| 변수 | 값 |
| --- | --- |
| `GF_SECURITY_ADMIN_USER` | `admin` |
| `GF_SECURITY_ADMIN_PASSWORD` | `admin` |

#### k6

| 변수 | 기본값 또는 compose 값 |
| --- | --- |
| `BASE_URL` | `http://app:8080` |
| `API_MODE` | `${API_MODE:-baseline}` |
| `BASE_PATH` | `${BASE_PATH:-}` |
| `PAYLOAD_PATH` | `/payload/enrollment_payload.csv` |
| `VUS` | `${VUS:-200}` |
| `ITERATIONS` | `${ITERATIONS:-}` |
| `LIMIT` | `${LIMIT:-}` |
| `SCENARIO_FILTER` | `${SCENARIO_FILTER:-}` |
| `IGNORE_SCHEDULE` | `${IGNORE_SCHEDULE:-false}` |
| `MAX_DURATION` | `${MAX_DURATION:-45s}` |
| `EXECUTOR_MODE` | `${EXECUTOR_MODE:-shared-iterations}` |
| `PEAK_RATE` | `${PEAK_RATE:-4800}` |
| `PEAK_DURATION` | `${PEAK_DURATION:-10s}` |
| `TAIL_RATE` | `${TAIL_RATE:-1600}` |
| `TAIL_DURATION` | `${TAIL_DURATION:-20s}` |
| `ARRIVAL_TIME_UNIT` | `${ARRIVAL_TIME_UNIT:-1s}` |
| `PRE_ALLOCATED_VUS` | `${PRE_ALLOCATED_VUS:-200}` |
| `MAX_VUS` | `${MAX_VUS:-200}` |

`k6-prometheus`에는 추가로 다음 변수가 있다.

| 변수 | 값 |
| --- | --- |
| `K6_PROMETHEUS_RW_SERVER_URL` | `http://prometheus:9090/api/v1/write` |
| `K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM` | `"false"` |

### 볼륨 마운트

| 서비스 | 마운트 |
| --- | --- |
| `postgres` | `./postgres_data:/var/lib/postgresql/data` |
| `prometheus` | `./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro` |
| `grafana` | `./grafana_data:/var/lib/grafana` |
| `grafana` | `./infra/grafana/provisioning:/etc/grafana/provisioning:ro` |
| `grafana` | `./infra/grafana/dashboards:/var/lib/grafana/dashboards:ro` |
| `k6` | `./k6:/scripts:ro` |
| `k6` | `./mock/sugang-mock/output/csv:/payload:ro` |
| `k6` | `./output/k6:/results` |
| `k6-prometheus` | `./k6:/scripts:ro` |
| `k6-prometheus` | `./mock/sugang-mock/output/csv:/payload:ro` |
| `k6-prometheus` | `./output/k6:/results` |

## 3. k6 스크립트 기능

대상 파일은 `k6/baseline-enrollment.js`이다. `scripts/baseline-enrollment.js` 파일은 현재 프로젝트에 없다.

### payload 로딩

- 기본 로컬 경로: `../mock/sugang-mock/output/csv/enrollment_payload.csv`
- compose 경로: `/payload/enrollment_payload.csv`
- 필수 컬럼: `student_id`, `course_id`, `scenario_type`, `expected_status`, `scheduled_offset_ms`
- `SharedArray` 사용:
  - `enrollment payloads`: 실제 실행 payload row 저장
  - `enrollment payload metadata`: row count, scenario distribution, expected status distribution 저장

CSV는 BOM 제거 후 line split과 comma split으로 파싱한다. CSV escape/quote 처리를 위한 별도 parser는 없다.

### scenario 처리

지원 scenario:

- `NORMAL`
- `HOTSPOT`
- `PREREQUISITE_FAIL`
- `TIME_CONFLICT`
- `CAPACITY_OVER`
- `DUPLICATE`

payload는 `scheduled_offset_ms` 오름차순, 이후 `request_id` 오름차순으로 정렬된다.

`SCENARIO_FILTER`가 비어 있거나 `ALL`이면 전체를 사용한다. 그 외에는 comma로 분리한 scenario만 필터링한다.

`LIMIT > 0`이면 정렬과 필터링 이후 앞에서부터 N건만 사용한다.

`ITERATIONS`가 있으면 실행 iteration 수만 제한한다. 없으면 `payloads.length`만큼 실행한다.

`VUS`는 요청값과 iteration 수 중 작은 값으로 보정된다.

### 스케줄 처리

`IGNORE_SCHEDULE=false`일 때:

- 테스트 시작 시각 `startedAt`을 기준으로 한다.
- 각 row의 `scheduled_offset_ms`를 더해 target time을 만든다.
- 현재 시각보다 target time이 미래이면 `sleep(waitMs / 1000)` 한다.

`IGNORE_SCHEDULE=true`일 때:

- `scheduled_offset_ms`를 무시하고 즉시 요청한다.

### 요청 형식

POST 대상:

```text
${BASE_URL}/api/baseline/enrollments
```

body:

```json
{
  "studentId": 1,
  "courseId": 1
}
```

header:

- `Content-Type: application/json`
- `X-Scenario-Type: <scenario_type>`

k6 tag:

- `scenario_type`
- `expected_status`

### expected_status 검증 방식

`isStrictMismatch(expectedStatus, actualStatus)` 기준:

| expected_status | strict match 조건 |
| --- | --- |
| `200` | 실제 HTTP status가 정확히 200 |
| `400` | 실제 HTTP status가 400 이상 500 미만 |
| 기타 값 | 실제 HTTP status가 `Number(expected_status)`와 동일 |

즉 `expected_status=400`은 400과 409 등 4xx 전체를 정상 도메인 실패로 인정한다.

### strict mismatch / critical mismatch

strict mismatch:

- `expected_status=200`인데 200이 아닌 경우
- `expected_status=400`인데 4xx가 아닌 경우
- 기타 expected status와 실제 status가 정확히 다른 경우

critical mismatch:

- system failure가 발생한 경우
- `expected_status=400`인데 실제 status가 200인 경우

custom metric:

- `baseline_strict_expected_status_mismatch_total`
- `baseline_critical_mismatch_total`
- `baseline_mismatch_sample_total`

strict mismatch가 발생하면 최대 20개 sample을 수집한다. sample 필드는 `request_id`, `scenario_type`, `expected_status`, `actual_status`, `student_id`, `course_id`, `response_body`이다.

### system failure 정의

다음 중 하나면 system failure로 본다.

- `response.error`가 존재
- HTTP status가 0
- HTTP status가 500 이상

custom metric:

- `baseline_system_failure_rate`
- `baseline_system_failure_total`

threshold:

- `baseline_system_failure_rate: rate<0.005`
- `baseline_critical_mismatch_total: count<1`
- `http_req_duration: p(95)<2000`, `p(99)<5000`

### scenario status count 출력

status bucket:

- `200`
- `400`
- `409`
- `500`

metric 이름:

```text
baseline_status_<scenario>_<status>_total
```

예:

- `baseline_status_normal_200_total`
- `baseline_status_hotspot_200_total`
- `baseline_status_prerequisite_fail_400_total`
- `baseline_status_capacity_over_409_total`

`handleSummary()`는 stdout에 다음 항목을 출력한다.

- scenario filter
- payload limit
- ignore schedule
- effective iterations
- requested/effective VUs
- loaded payload rows
- requests
- request rate
- strict mismatch count
- critical mismatch count
- system failure rate/count
- latency p95/p99
- payload scenario distribution
- payload expected_status distribution
- scenario status count
- response body reason count
- mismatch samples

주의: 현재 `handleSummary()`는 stdout에 표시할 커스텀 텍스트 요약만 반환한다. JSON 결과 파일은 compose command의 `--summary-export /results/baseline-summary.json` 옵션으로 생성한다.

## 4. 현재 확인된 테스트 실행 결과

기준 파일:

- `output/k6/baseline-summary.json`

이 파일에 남아 있는 실행 결과는 80,000 iterations 전체 실행 결과이다.

### 전체 요약

| 항목 | 값 |
| --- | --- |
| 전체 payload 실행 여부 | 실행됨. `iterations.count=80000`, `http_reqs.count=80000` |
| request rate | `934.1758993166784 req/s` |
| p95 | `660.49641105 ms` |
| p99 | `720.4651167599997 ms` |
| checks | `passes=240000`, `fails=0` |
| strict mismatch count | 0으로 확인됨. 해당 counter는 0회라 summary JSON metrics에 key가 생성되지 않았고, `strict expected status matched` check가 `passes=80000`, `fails=0`이다. |
| critical mismatch count | `0` |
| system failure count | 0으로 확인됨. 해당 counter는 0회라 summary JSON metrics에 key가 생성되지 않았고, `no system failure` check가 `passes=80000`, `fails=0`이다. |
| system failure rate | `0` |

참고: `http_req_failed.value=0.2`가 존재한다. 이는 4xx 도메인 실패 응답까지 k6 기본 실패로 계산하기 때문이다. 이 프로젝트의 시스템 실패 기준은 `baseline_system_failure_rate`이다.

### payload 분포

현재 `mock/sugang-mock/output/csv/enrollment_payload.csv` 기준:

| scenario_type | count |
| --- | ---: |
| `NORMAL` | 32,000 |
| `HOTSPOT` | 32,000 |
| `CAPACITY_OVER` | 6,400 |
| `PREREQUISITE_FAIL` | 3,200 |
| `TIME_CONFLICT` | 3,200 |
| `DUPLICATE` | 3,200 |

expected status 분포:

| expected_status | count |
| --- | ---: |
| `200` | 64,000 |
| `400` | 16,000 |

### scenario별 status count

`output/k6/baseline-summary.json`에 존재하는 metric 기준:

| scenario | status count |
| --- | --- |
| `NORMAL` | `200=32000` |
| `HOTSPOT` | `200=32000` |
| `PREREQUISITE_FAIL` | `400=3200` |
| `TIME_CONFLICT` | `409=3200` |
| `CAPACITY_OVER` | `409=6400` |
| `DUPLICATE` | `409=3200` |

0회 status bucket metric은 summary JSON에 생성되지 않는다.

### response body reason count

`output/k6/baseline-summary.json`에 존재하는 metric 기준:

| reason | count |
| --- | ---: |
| `PrerequisiteNotMetException` | 3,200 |
| `TimeConflictException` | 3,200 |
| `CapacityExcessException` | 9,600 |

`CapacityExcessException` reason count는 9,600이다. scenario status count에서는 `CAPACITY_OVER=6400`이고, 추가 3,200건은 다른 scenario의 응답 body reason이 capacity excess로 기록된 결과다. 원인 분석은 이 문서 범위 밖이며, 현재 summary JSON의 사실만 기록한다.

`DuplicateEnrollmentException`, `AlreadyCompletedCourseException`, `Unknown` reason counter는 현재 summary JSON에 key가 없다.

## 5. Prometheus / Grafana / Actuator 연동 상태

### Spring Boot Actuator

`src/main/resources/application.yml` 기준 활성 endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

활성 endpoint:

- `/actuator/health`
- `/actuator/info`
- `/actuator/metrics`
- `/actuator/prometheus`

health detail:

- `management.endpoint.health.show-details=always`

Micrometer 설정:

- `management.metrics.tags.application=sugang-v2-baseline`
- `http.server.requests` percentiles histogram 활성화
- `http.server.requests` percentile `0.95`, `0.99` 설정

의존성:

- `spring-boot-starter-actuator`
- `io.micrometer:micrometer-registry-prometheus`

### Prometheus

`infra/prometheus/prometheus.yml` 기준:

```yaml
global:
  scrape_interval: 5s
```

scrape target:

| job_name | metrics_path | targets |
| --- | --- | --- |
| `sugang-v2-baseline` | `/actuator/prometheus` | `app:8080` |
| `postgres-exporter` | 기본값 | `postgres-exporter:9187` |

compose command:

```text
--web.enable-remote-write-receiver
```

따라서 `k6-prometheus`의 `experimental-prometheus-rw` output을 받을 수 있도록 설정되어 있다.

현재 `k6-prometheus`는 `K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=false`로 설정한다. Prometheus를 native histogram feature 없이 실행하는 상태에서 이 값을 `true`로 두면 `/api/v1/write`가 `native histograms are disabled` 에러와 함께 500을 반환한다.

### Grafana datasource

`infra/grafana/provisioning/datasources/prometheus.yml` 기준:

| 항목 | 값 |
| --- | --- |
| name | `Prometheus` |
| uid | `Prometheus` |
| type | `prometheus` |
| access | `proxy` |
| url | `http://prometheus:9090` |
| isDefault | `true` |

### Grafana dashboard provisioning

`infra/grafana/provisioning/dashboards/baseline.yml` 기준:

| 항목 | 값 |
| --- | --- |
| provider name | `Baseline` |
| folder | `Sugang Baseline` |
| type | `file` |
| path | `/var/lib/grafana/dashboards` |
| updateIntervalSeconds | `10` |

### Dashboard

`infra/grafana/dashboards/baseline-dashboard.json` 기준:

| 항목 | 값 |
| --- | --- |
| title | `Sugang Baseline Load Test` |
| uid | `sugang-baseline-load-test` |
| datasource uid | `Prometheus` |

패널 목록:

| 패널 | 주요 PromQL |
| --- | --- |
| `Request rate` | `sum(rate(http_server_requests_seconds_count{job="sugang-v2-baseline", uri=~"/api/(baseline\|optimistic)/enrollments"}[1m]))`, `sum(rate(k6_http_reqs_total[1m]))` |
| `P95/P99 latency` | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job="sugang-v2-baseline", uri=~"/api/(baseline\|optimistic)/enrollments"}[1m])) by (le))`, `histogram_quantile(0.99, ...)`, `max(k6_http_req_duration_p95{name=~"http://app:8080/api/(baseline\|optimistic)/enrollments"}) / 1000`, `max(k6_http_req_duration_p99{name=~"http://app:8080/api/(baseline\|optimistic)/enrollments"}) / 1000` |
| `HTTP status ratio` | baseline/optimistic enrollment endpoint의 2xx/4xx/5xx별 `http_server_requests_seconds_count` 비율 |
| `HikariCP active/pending` | `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_max` |
| `PostgreSQL connections` | `sum(pg_stat_activity_count{job="postgres-exporter"}) by (state)` |
| `PostgreSQL lock wait / deadlock` | `sum(pg_stat_activity_wait_count{job="postgres-exporter", datname="enrollment", wait_event_type="Lock"}) or vector(0)`, `sum(rate(pg_stat_database_deadlocks{job="postgres-exporter"}[1m]))` |

### 미구현 또는 TODO

- Grafana dashboard에는 k6 Prometheus remote-write metric(`k6_http_reqs_total`, `k6_http_req_duration_p95`, `k6_http_req_duration_p99`) 패널이 있지만, 기본 `k6` profile은 remote-write를 사용하지 않는다. 이 metric은 `load-prometheus` profile로 `k6-prometheus`를 실행해야 Prometheus에 들어간다.
- 별도 Micrometer custom metric을 Spring 애플리케이션 코드에서 직접 등록하는 구현은 없다. 현재 Spring 쪽은 Actuator/Micrometer 자동 계측과 HikariCP metric에 의존한다.
- dashboard에 JVM memory 패널은 없다. docs에는 `jvm_memory_used_bytes`가 수집 지표로 언급되어 있지만 dashboard JSON 패널 목록에는 없다.

## 6. PostgreSQL / HikariCP / Micrometer 관련 설정

### PostgreSQL

compose:

- image: `postgres:16`
- port: `5432:5432`
- database: `enrollment`
- user/password: `user` / `password`
- data volume: `./postgres_data:/var/lib/postgresql/data`

schema/load/reset:

- `infra/postgres/schema.sql`
- `infra/postgres/load.sql`
- `infra/postgres/reset.sql`

### JPA transaction / lock

`BaselineEnrollmentService.enroll()`은 `@Transactional`이다.

`CourseRepository.findByIdWithPessimisticLock()`은 다음 설정을 사용한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Course c where c.id = :id")
Optional<Course> findByIdWithPessimisticLock(Long id);
```

`CourseRepository.findByIdWithOptimisticLock()`도 존재하지만, 현재 `BaselineEnrollmentService`에서는 pessimistic lock 메서드를 사용한다.

### HikariCP

`src/main/resources/application.yml` 기준:

| 설정 | 값 |
| --- | --- |
| maximum pool size | `${HIKARI_MAXIMUM_POOL_SIZE:16}` |
| connection timeout | `${HIKARI_CONNECTION_TIMEOUT_MS:8000}` |
| pool name | `baseline-hikari` |

compose에서 app에 주입하는 값:

- `HIKARI_MAXIMUM_POOL_SIZE=16`
- `HIKARI_CONNECTION_TIMEOUT_MS=8000`

### Micrometer / Actuator

`build.gradle`에 `micrometer-registry-prometheus` 의존성이 있다.

`application.yml`에서 Prometheus endpoint를 노출하고, `http.server.requests` histogram과 p95/p99 percentile을 설정한다.

Spring 애플리케이션 코드에서 직접 `Counter`, `Timer`, `Gauge` 등을 등록하는 구현은 현재 없다.

## 7. 중요한 설계 의도

이 섹션은 README와 docs에 실제로 적힌 내용, 그리고 k6 스크립트에 구현된 검증 기준만 정리한다.

### Redis/MQ를 제외한 이유

README와 `docs/load-test-environment.md`에 따르면 Redis, MQ, Kafka, 비동기 큐는 병목을 완화하거나 요청을 흡수하는 장치다. Baseline 단계에서는 이를 제외해 동기식 RDB 구조의 실제 한계를 관측한다.

관측 대상:

- PostgreSQL row lock 경합
- WAS-RDB 구조의 P95/P99와 TPS 한계
- 정원 초과, 중복 신청, 선수과목, 시간표 충돌이 DB transaction 안에서 방어되는지 여부

### k6 검증 모드를 만든 이유

`k6/baseline-enrollment.js`는 단순히 HTTP latency만 측정하지 않는다. payload의 `expected_status`와 실제 status를 비교하고, 도메인 실패와 시스템 실패를 분리한다.

구현된 검증:

- `strict expected status matched`
- `no critical mismatch`
- `no system failure`
- scenario별 status counter
- response body reason counter
- mismatch sample 출력

### IGNORE_SCHEDULE=true 검증 모드와 Burst 테스트 모드 분리

docs에 따르면 smoke test는 API/검증 기준 확인이 목적이므로 `IGNORE_SCHEDULE=true`로 `scheduled_offset_ms`를 무시하고 즉시 실행한다.

burst test는 `scheduled_offset_ms`를 테스트 시작 기준 오프셋으로 사용한다. 따라서 Mock Data Harness가 만든 요청 타이밍 분포를 반영한다.

### 4xx를 시스템 실패로 보지 않는 이유

docs와 k6 구현 기준:

- 도메인 실패 payload는 4xx로 방어되면 정상 검증 통과로 본다.
- 5xx 또는 네트워크 실패는 API/DB 병목이 사용자 오류를 넘어 서버 실패로 전이된 신호로 본다.
- k6 기본 `http_req_failed`는 4xx도 실패로 계산할 수 있으므로 threshold로 사용하지 않는다.
- 대신 `baseline_system_failure_rate`를 시스템 실패 기준으로 사용한다.

### strict mismatch와 critical mismatch를 분리한 이유

구현 기준:

- strict mismatch는 expected status와 실제 status 분류가 어긋난 모든 경우를 관측한다.
- critical mismatch는 반드시 실패로 봐야 하는 경우만 집계한다.

critical mismatch 조건:

- 5xx 또는 네트워크 실패
- `expected_status=400`인 도메인 실패 payload가 200으로 성공한 경우

따라서 4xx 도메인 실패의 세부 status 차이는 strict mismatch에서 관측하고, 시스템 실패나 도메인 방어 실패는 critical mismatch로 분리한다.

## 블로그에 반드시 넣어야 할 구현 포인트

1. Compose는 `app`, `postgres`, `postgres-exporter`, `prometheus`, `grafana`, `k6`, `k6-prometheus`로 구성되며 Redis/MQ는 의도적으로 제외되어 있다.
2. k6는 `SharedArray`로 80,000건 payload CSV를 로드하고 `SCENARIO_FILTER`, `LIMIT`, `ITERATIONS`, `VUS`, `MAX_DURATION`, `IGNORE_SCHEDULE`로 실행 모드를 바꾼다.
3. `expected_status=400`은 4xx 전체를 정상 도메인 실패로 인정하고, 5xx/네트워크 실패만 system failure로 분리한다.
4. Actuator Prometheus endpoint와 postgres-exporter를 Prometheus가 scrape하고, Grafana dashboard는 request rate, latency, status ratio, HikariCP, PostgreSQL lock/deadlock을 본다.
5. 현재 summary JSON 기준 80,000 requests 전체 실행 결과는 request rate 약 `934.18 req/s`, p95 약 `660.50 ms`, p99 약 `720.47 ms`, critical mismatch 0, system failure 0이다.
