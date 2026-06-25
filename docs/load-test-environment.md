# Load Test Environment

## 목적

이 단계의 목적은 Spring Boot + PostgreSQL 기반 Baseline 수강신청 API에 k6 부하를 주입하고, 병목 지표를 관측할 수 있는 환경을 구축하는 것이다.

중요한 점은 성능 개선이 아니라 측정 가능성이다. Redis, MQ, Kafka, 비동기 큐는 이번 단계에서 사용하지 않는다. Baseline의 병목을 캐시나 큐로 숨기지 않고, 동기식 RDB 구조의 한계를 그대로 관찰한다.

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
| `app` | Baseline 수강신청 API |
| `postgres` | 수강신청 DB |
| `postgres-exporter` | PostgreSQL 지표 수집 |
| `prometheus` | Spring/PostgreSQL/k6 지표 저장 |
| `grafana` | Dashboard 시각화 |
| `k6` | summary JSON 기반 부하 테스트 |
| `k6-prometheus` | Prometheus remote-write 기반 부하 테스트 |

## 제외한 것

- Redis
- MQ
- Kafka
- RabbitMQ
- 비동기 대기열
- 캐시 기반 정원 차감

Baseline의 목적은 DB row lock, connection pool, SQL/FK/unique constraint 기반 방어가 어떤 병목을 만드는지 보는 것이다.

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

### 2. 관측 스택 실행

```bash
docker compose up --build -d app postgres postgres-exporter prometheus grafana
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

### 3. DB schema 생성 및 Mock Data 적재

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/schema.sql
PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/load.sql
```

반복 테스트 전 초기화:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment -f infra/postgres/reset.sql
```

### 4. summary JSON 기반 k6 실행

```bash
docker compose --profile load run --rm k6
```

결과 파일:

```text
output/k6/baseline-summary.json
```

로컬에서 직접 실행할 수도 있다.

```bash
k6 run k6/baseline-enrollment.js \
  --summary-export output/k6/baseline-summary.json
```

### 5. Prometheus remote-write 기반 k6 실행

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
| `200` | HTTP 200 |
| `400` | HTTP 4xx |

도메인 실패 payload는 4xx로 방어되면 성공으로 본다. 5xx는 Baseline API 또는 DB 병목이 사용자 오류를 넘어 서버 실패로 전이된 신호다.

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

### PostgreSQL / postgres-exporter

| 지표 | 의미 |
| --- | --- |
| `pg_stat_activity_count` | DB connection/session 상태 |
| `pg_stat_database_deadlocks` | deadlock 발생 수 |
| `pg_locks_count` | lock 상태 |

### k6

| 지표 | 의미 |
| --- | --- |
| `http_reqs` | 요청 수/TPS |
| `http_req_duration` | end-to-end latency |
| `http_req_failed` | k6 check 기준 실패율 |
| `baseline_expected_status_mismatch_total` | expected_status와 실제 status 불일치 수 |
| `baseline_scenario_requests_total` | scenario_type별 요청 수 |

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
