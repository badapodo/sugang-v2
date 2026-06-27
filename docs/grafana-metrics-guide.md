# Grafana 부하 테스트 지표 해설

## 목적

이 문서는 `Sugang Baseline Load Test` Grafana dashboard에 표시되는 지표의 의미와 해석 방법을 정리한다.

기준 파일:

```text
infra/grafana/dashboards/baseline-dashboard.json
```

Dashboard는 다음 데이터를 함께 사용한다.

| 데이터 원천 | 수집 내용 |
| --- | --- |
| Spring Boot Actuator / Micrometer | HTTP, HikariCP, JVM, Global Single Writer |
| k6 Prometheus remote write | 요청률, latency, status 0/5xx |
| postgres-exporter | PostgreSQL connection, lock wait, deadlock |

## 지표 형식

| 형식 | 의미 | 해석 방법 |
| --- | --- | --- |
| Counter | 시작 후 누적 횟수 | `rate(metric[1m])`로 초당 변화량 확인 |
| Gauge | 현재 상태 | queue depth, connection, inflight처럼 현재값 확인 |
| Histogram | 측정값의 구간별 누적 횟수 | `histogram_quantile()`로 p95/p99 계산 |
| Ratio | 전체 중 특정 상태의 비율 | 0~1 값이며 Grafana에서 백분율로 표시 |

애플리케이션 재시작 시 애플리케이션 Counter는 0부터 다시 시작한다.

## 공통 HTTP 및 DB 패널

### 1. Request rate

표시 항목:

- `Spring request rate`: Spring이 실제로 받은 초당 HTTP 요청 수
- `k6 request rate`: k6가 전송한 초당 요청 수

두 값이 비슷하면 k6 요청이 애플리케이션까지 정상 도달한 것이다. k6 요청률만 높고 Spring 요청률이 낮으면 connection 실패, status 0, 네트워크 backlog 또는 애플리케이션 accept 지연을 확인한다.

### 2. P95/P99 latency

- p95: 요청의 95%가 이 시간 안에 완료
- p99: 요청의 99%가 이 시간 안에 완료
- Spring latency: 서버가 요청을 받은 뒤 응답할 때까지의 시간
- k6 latency: k6 관점의 네트워크 구간을 포함한 시간

p99만 크게 증가하면 일부 요청이 queue, lock, connection 대기에 오래 머무는 tail latency 현상이다. k6 latency가 Spring latency보다 훨씬 크면 서버 처리 외의 연결·전송 대기를 함께 의심한다.

### 3. HTTP status ratio

| 계열 | 의미 |
| --- | --- |
| 2xx | 처리 또는 접수 성공 |
| 4xx | 도메인 실패, queue full 등 클라이언트 계열 응답 |
| 5xx | 서버 오류 또는 timeout |

현재 payload에는 의도된 실패 시나리오가 포함되어 있으므로 4xx가 0일 필요는 없다. `PREREQUISITE_FAIL`, `TIME_CONFLICT`, `CAPACITY_OVER`, `DUPLICATE`의 4xx는 정상 검증 결과다. 시스템 안정성은 5xx와 k6 system failure를 중심으로 판단한다.

### 4. HikariCP active/pending

| 항목 | 의미 |
| --- | --- |
| active | 사용 중인 DB connection |
| pending | connection을 기다리는 thread |
| max | pool의 최대 connection 수 |

`active`가 `max`에 붙고 `pending`이 지속적으로 증가하면 connection pool이 포화된 상태다. Global In-Memory 방식에서는 HTTP 판정 경로가 DB를 사용하지 않으므로, 이 현상은 주로 write-behind 병목과 함께 해석한다.

### 5. PostgreSQL connections

`pg_stat_activity`를 상태별로 합산한 DB session 수다.

| state | 의미 |
| --- | --- |
| active | SQL을 실행 중인 session |
| idle | 연결은 유지하지만 작업하지 않는 session |
| idle in transaction | transaction을 종료하지 않고 대기 중인 session |

`idle in transaction`이 지속되면 transaction 범위나 예외 처리 누락을 점검한다. active 증가만으로 병목을 확정하지 않고 Hikari pending과 DB latency를 함께 본다.

### 6. PostgreSQL lock wait / deadlock

- `lock wait sessions`: 현재 `wait_event_type="Lock"`인 session 수
- `deadlocks/sec`: PostgreSQL이 탐지한 deadlock 증가율

lock wait는 다른 transaction의 lock 해제를 기다리는 상태다. deadlock은 PostgreSQL이 순환 대기를 탐지하여 transaction 하나를 중단한 사건이다.

`deadlocks/sec=0`이어도 lock 경합이 없다는 뜻은 아니다. lock wait는 발생하지만 순환 대기가 아니면 deadlock은 0으로 유지된다.

## Global In-Memory Single Writer 패널

### 7. Global In-Memory Single Writer

Grafana row 제목이다. 아래 패널을 Global Writer 처리 단계와 write-behind 처리 단계로 묶는다.

### 8. Global Writer throughput

| 항목 | 의미 |
| --- | --- |
| enqueued/sec | HTTP thread가 global command queue에 넣은 요청률 |
| processed/sec | writer가 성공 또는 실패로 판정을 완료한 요청률 |
| success/sec | writer가 메모리 수강신청 성공으로 판정한 요청률 |

`enqueued/sec > processed/sec` 상태가 지속되면 처리하지 못한 command가 queue에 누적된다. `processed - success` 차이에는 정상적인 도메인 실패도 포함된다.

### 9. Global Writer queue depth

단일 writer가 아직 꺼내지 않은 command 수다.

일시적인 상승은 burst 흡수 과정일 수 있다. 테스트 유입률이 낮아진 뒤에도 감소하지 않거나 계속 증가하면 writer 처리량이 유입률보다 낮다.

### 10. Global Writer match latency p95/p99

writer가 command 하나를 꺼낸 뒤 다음 작업을 완료하는 데 걸린 시간이다.

- 선수과목 검증
- 중복 검증
- 시간표 충돌 검증
- 정원 검증
- 메모리 상태 변경
- write-behind queue 전달
- future 완료

이 값은 command가 writer queue에서 기다린 시간은 포함하지 않는다. match latency는 낮은데 HTTP wait가 높으면 queue 대기를 확인한다.

### 11. HTTP response wait latency p95/p99

HTTP thread가 command enqueue를 시작한 시점부터 writer 결과를 받을 때까지의 시간이다.

대략 다음 시간이 포함된다.

```text
command queue 대기 + match 처리 + future 전달
```

match latency보다 훨씬 크면 global queue 대기가 주요 지연 원인이다.

### 12. In-flight HTTP waiting requests

command enqueue에 성공한 뒤 future 결과를 기다리는 HTTP 요청 수다.

queue depth, response wait latency와 함께 증가하면 Tomcat thread가 writer 결과를 기다리며 누적되는 상태다. timeout이 발생하면 `global_single_writer_timeout_total`도 확인한다.

### 13. Write-behind queue depth

메모리 성공 판정 후 아직 DB worker가 꺼내지 않은 성공 event 수다.

writer queue는 안정적인데 이 값만 증가하면 메모리 matching보다 DB 영속화가 느린 상태다. Hikari pending, PostgreSQL connection, write-behind lag를 함께 확인한다.

### 14. Write-behind success/failure rate

| 항목 | 의미 |
| --- | --- |
| processed/sec | DB worker가 queue에서 꺼낸 event 수 |
| success/sec | DB insert 성공률 |
| failed reason/sec | reason별 DB 저장 실패율 |

`processed/sec > success/sec` 차이가 지속되면 failure reason을 확인한다. 메모리 성공은 이미 HTTP 200으로 반환됐기 때문에 write-behind 실패가 자동으로 HTTP 응답을 롤백하지 않는다.

주요 failure reason:

| reason | 의미 |
| --- | --- |
| `AlreadyExistsBeforeInsert` | insert 전에 동일 student/course가 DB에 존재 |
| `DataIntegrityViolationException` | 사전 조회 이후 insert 시점에 unique constraint 충돌 |
| `DuplicateCommandEnqueue` | 동일 commandId의 중복 enqueue |
| `DuplicateCommandProcessed` | 동일 commandId의 중복 consume |

### 15. Write-behind duplicate insert count

- `duplicates`: DB에 동일 pair가 존재하거나 unique constraint 충돌로 분류된 누적 횟수
- `duplicate command enqueue`: 동일 commandId enqueue 탐지 수
- `duplicate command processed`: 동일 commandId consume 탐지 수

`duplicates`만 증가하고 command 중복 지표가 0이면 동일 command 재처리보다는 DB와 in-memory snapshot 불일치를 먼저 확인한다.

서로 다른 API 모드는 메모리 상태를 공유하지 않지만 같은 DB를 사용한다. API 모드를 바꿔 비교할 때는 다음 순서를 지킨다.

```bash
PGPASSWORD=password psql -h localhost -U user -d enrollment \
  -f infra/postgres/reset.sql

docker compose restart app
```

### 16. Write-behind latency p95/p99

| 항목 | 의미 |
| --- | --- |
| DB p95/p99 | worker가 DB 존재 확인과 insert를 처리한 시간 |
| queue lag p95/p99 | event enqueue부터 DB worker가 처리를 시작할 때까지의 시간 |

DB latency가 증가하면 connection/SQL/DB 부하를 확인한다. DB latency는 안정적인데 queue lag만 증가하면 worker 수 대비 event 유입량이 많거나 worker scheduling이 지연되는 상태다.

### 17. Domain failure count by reason

Global Writer가 반환한 도메인 실패 누적 횟수다.

예:

- `PrerequisiteNotMetException`
- `TimeConflictException`
- `DuplicateEnrollmentException`
- `CapacityExcessException`

도메인 실패는 시스템 장애가 아니다. payload scenario별 예상 분포와 일치하는지 k6 summary의 status/reason count와 비교한다.

### 18. Global API system failure / 5xx

| 항목 | 의미 |
| --- | --- |
| Spring 5xx/sec | Global API가 반환한 서버 오류 |
| k6 status 0/5xx/sec | 네트워크 실패와 5xx를 포함한 k6 system failure |
| writer timeout/sec | HTTP thread가 writer 결과를 제한 시간 안에 받지 못한 횟수 |

status 0은 Spring이 반환한 HTTP status가 아니라 k6가 응답을 받지 못한 경우다. 따라서 Spring 5xx가 0이어도 k6 system failure가 증가할 수 있다.

### 19. Writer success / write-behind enqueue consistency

| 항목 | 의미 |
| --- | --- |
| writer success | 메모리 성공 판정 누적 수 |
| write-behind enqueued | DB 저장 event enqueue 누적 수 |
| gap | `writer success - write-behind enqueued` |

정상 상태에서 gap은 0이다. gap이 증가하면 메모리 상태는 성공으로 변경됐지만 write-behind event 전달이 완료되지 않은 경로를 조사한다.

### 20. JVM heap memory

- `used`: 현재 사용 중인 heap
- `max`: JVM이 사용할 수 있는 최대 heap

used가 계속 증가하고 GC 후에도 내려오지 않으면 command 추적 set, queue backlog 또는 객체 보존을 점검한다. 순간 상승만으로 memory leak을 판단하지 않고 GC 패널과 함께 본다.

### 21. JVM GC pause

- `pause seconds/sec`: 1초당 GC pause에 사용된 시간
- `collections/sec`: 1초당 GC 발생 횟수

GC pause와 HTTP latency가 동시에 증가하면 메모리 압력 또는 과도한 객체 생성이 응답 지연에 영향을 주는지 확인한다.

## Startup 정합성 지표

Dashboard 패널에는 포함하지 않았지만 Prometheus에서 다음 값을 조회할 수 있다.

```promql
global_single_writer_loaded_seed_enrollments
global_single_writer_loaded_in_memory_enrollments
global_single_writer_loaded_course_states
global_single_writer_loaded_student_timetables
```

정상 시작 조건:

```text
loaded_seed_enrollments == loaded_in_memory_enrollments
```

현재 mock seed 기준 enrollment와 timetable entry는 각각 12,900개이고 course state는 4,000개다.

## 병목 판정 순서

1. `Request rate`에서 k6 요청이 Spring까지 도달했는지 확인한다.
2. `HTTP status ratio`와 `Global API system failure / 5xx`에서 시스템 실패 여부를 확인한다.
3. `Global Writer queue depth`, `command lag`, `processed/sec`로 writer 병목을 확인한다.
4. `Write-behind queue depth`, queue lag, DB latency로 영속화 병목을 확인한다.
5. HikariCP와 PostgreSQL 패널에서 DB connection/lock 원인을 확인한다.
6. JVM heap/GC가 같은 시간대에 증가했는지 확인한다.

## 대표적인 조합

| 관측 조합 | 해석 |
| --- | --- |
| writer queue 증가 + command lag 증가 | 단일 writer 처리량 부족 |
| writer queue 안정 + write-behind queue 증가 | DB 영속화 처리량 부족 |
| Hikari pending 증가 + DB latency 증가 | DB connection 또는 SQL 지연 |
| lock wait 증가 + baseline latency 증가 | pessimistic row lock 경합 |
| duplicate 증가 + command 중복 0 | DB와 in-memory snapshot 불일치 가능성 |
| Spring 5xx 0 + k6 system failure 증가 | connection 실패/status 0 가능성 |
| heap/GC pause 증가 + p99 증가 | JVM memory pressure 가능성 |

## 데이터가 보이지 않을 때

- k6 지표는 `load-prometheus` profile의 `k6-prometheus`로 실행해야 한다.
- 앱 metric은 `http://localhost:8080/actuator/prometheus`에서 확인한다.
- Prometheus target은 `http://localhost:9090/targets`에서 확인한다.
- Grafana datasource는 provisioning된 `Prometheus` UID를 사용한다.
- Counter에 아직 사건이 한 번도 없으면 reason label metric이 생성되지 않아 패널에 값이 없을 수 있다.
