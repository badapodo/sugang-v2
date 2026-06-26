# sugang-v2

동기식 RDB 락 기반 수강신청 API Baseline 프로젝트.

이 프로젝트는 `tmp/sugang`의 기존 구현에서 Baseline에 필요한 JPA 엔티티/Repository만 가져와 별도 프로젝트로 정리한 버전이다. Redis, MQ, 비동기 큐, JWT 인증은 의도적으로 제외했다.

## 목표

Mock Data Harness가 만든 8만 건 수강신청 payload를 사용해 다음을 측정한다.

- Hotspot 과목에서 PostgreSQL row lock 경합이 어떻게 발생하는가
- 동기식 WAS-RDB 구조의 P95/P99와 TPS 한계는 어디인가
- 정원 초과, 중복 신청, 선수과목, 시간표 충돌이 DB 트랜잭션 안에서 방어되는가

## 구성

```text
k6
→ Spring Boot /api/baseline/enrollments
→ JPA @Transactional
→ PostgreSQL SELECT ... FOR UPDATE
```

## 주요 파일

| 파일 | 설명 |
| --- | --- |
| `src/main/java/badapodo/sugang/service/BaselineEnrollmentService.java` | 동기식 RDB Baseline 수강신청 로직 |
| `src/main/java/badapodo/sugang/controller/BaselineEnrollmentController.java` | 인증 없는 Baseline API |
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

```http
POST /api/baseline/enrollments
Content-Type: application/json

{
  "studentId": 1001,
  "courseId": 20
}
```

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
