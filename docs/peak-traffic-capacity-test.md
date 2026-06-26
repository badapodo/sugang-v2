# Peak Traffic Capacity Test

이 문서는 80,000건 수강신청 요청이 30초 동안 들어오는 상황에서 초반 10초에 60%가 집중되는 피크 트래픽을 k6 `constant-arrival-rate`로 검증하는 방법을 정리한다.

## 목표 모델

Capacity Planning 기준:

| 구간 | 비율 | 요청 수 | 시간 | 목표 유입률 |
| --- | ---: | ---: | ---: | ---: |
| 피크 구간 | 60% | 48,000 | 10초 | 4,800 req/s |
| 후속 구간 | 40% | 32,000 | 20초 | 1,600 req/s |
| 합계 | 100% | 80,000 | 30초 | - |

목표:

- 4,800 쓰기 TPS 순간 유입을 주입한다.
- 전체 30초 트래픽에서 `dropped_iterations=0`을 만족한다.
- `baseline_critical_mismatch_total=0`을 만족한다.
- `baseline_system_failure_rate < 0.005`를 만족한다.
- P99가 대기열 마지노선인 5초 미만인지 확인한다.

## 구현 방식

`k6/baseline-enrollment.js`는 기본적으로 `shared-iterations`를 사용한다.

피크 트래픽 테스트는 다음 환경변수로 `constant-arrival-rate` 기반 시나리오를 사용한다.

```bash
EXECUTOR_MODE=peak-arrival-rate
```

이 모드에서는 두 개의 k6 scenario가 실행된다.

| scenario | executor | exec function | rate | duration | startTime |
| --- | --- | --- | ---: | --- | --- |
| `peak_arrival` | `constant-arrival-rate` | `peakArrival` | `PEAK_RATE` | `PEAK_DURATION` | 즉시 |
| `tail_arrival` | `constant-arrival-rate` | `tailArrival` | `TAIL_RATE` | `TAIL_DURATION` | `PEAK_DURATION` 이후 |

`peak-arrival-rate` 모드에서는 `scheduled_offset_ms`를 사용하지 않는다. 유입 시점은 k6 arrival-rate executor가 통제한다.

## 실행 전 준비

DB 초기화:

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql
```

App / Observability stack 실행:

```bash
docker compose up --build -d app postgres-exporter prometheus grafana
```

## Smoke Test

먼저 작은 유입률로 스크립트와 remote-write 경로를 검증한다.

```bash
EXECUTOR_MODE=peak-arrival-rate \
SCENARIO_FILTER=ALL \
PEAK_RATE=5 PEAK_DURATION=2s \
TAIL_RATE=2 TAIL_DURATION=2s \
PRE_ALLOCATED_VUS=5 MAX_VUS=20 \
docker compose --profile load-prometheus run --rm k6-prometheus
```

확인할 것:

- k6가 두 scenario(`peak_arrival`, `tail_arrival`)를 생성하는지
- `dropped iterations: 0`인지
- Prometheus/Grafana에 k6 metric이 들어오는지

## Capacity Planning Test

80,000건 중 48,000건을 첫 10초에, 32,000건을 이후 20초에 주입한다.

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

`PRE_ALLOCATED_VUS`와 `MAX_VUS`는 테스트 장비 성능에 맞춰 조정한다. 목표 유입률이 높은데 VU가 부족하면 k6가 요청을 시작하지 못하고 `dropped_iterations`가 증가한다.

## 판정 기준

성공 기준:

| 항목 | 기준 |
| --- | --- |
| `dropped_iterations` | 0 |
| `baseline_critical_mismatch_total` | 0 |
| `baseline_system_failure_rate` | `< 0.005` |
| `http_req_duration p(99)` | `< 5000ms` |

해석:

- `dropped_iterations > 0`: k6가 목표 유입률을 주입하지 못한 상태다. 서버 병목 이전에 k6 VU 또는 부하 생성기 자원이 부족할 수 있다.
- `p99 >= 5000ms`: 대기열 마지노선 5초를 초과한다.
- `baseline_system_failure_rate >= 0.005`: 5xx 또는 네트워크 실패가 허용 범위를 넘었다.
- `baseline_critical_mismatch_total > 0`: 도메인 실패가 200으로 성공했거나 시스템 실패가 발생했다.

## 주의사항

4,800 req/s는 k6 실행 환경에도 큰 부하다. 테스트 장비의 CPU, network, Docker resource가 부족하면 애플리케이션 한계가 아니라 부하 생성기 한계를 먼저 만날 수 있다.

부하 생성기 한계가 의심될 때 확인할 것:

- `dropped_iterations`
- k6 container CPU 사용률
- k6 container memory 사용률
- `PRE_ALLOCATED_VUS`, `MAX_VUS`
- k6와 app을 같은 host에서 실행해 생기는 resource contention

실제 운영 한계를 더 정확히 보려면 k6 실행기와 app/db host를 분리하는 것이 좋다.
