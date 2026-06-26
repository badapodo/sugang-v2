# ADR-003. Queue 기반 수강신청 구조를 도입할 것인가?

## Status
Proposed

## Context
현재 프로젝트에는 세 가지 실험 기준선이 있다.

1. PESSIMISTIC_WRITE 기반 Baseline API
   - endpoint: `POST /api/baseline/enrollments`
   - 같은 `course` row에 `SELECT ... FOR UPDATE` 경합이 집중된다.
   - Peak arrival-rate 테스트에서 connection pool을 16에서 32로 늘려도 system failure rate는 약 50% 수준으로 유지되었다.
   - Pool 32에서도 request rate는 약 501 req/s, dropped iterations는 약 49,772, p99는 약 9.6초였다.
   - lock wait session은 Pool 16 약 14~15에서 Pool 32 약 31로 증가했다.

2. Connection Pool scaling 실험
   - Pool 증가는 처리량을 소폭 개선했지만 근본 해결책이 아니었다.
   - DB row lock 경합이 유지되는 상태에서 connection만 늘리면 lock wait와 DB 부하가 증가한다.
   - ADR-001 결론: Connection Pool 크기 증가는 단독 개선안으로 채택하지 않는다.

3. Optimistic Lock 실험
   - endpoint: `POST /api/optimistic/enrollments`
   - latency와 lock wait는 크게 줄었다.
   - request rate는 약 1,724 req/s, p99는 약 230ms, system failure rate는 1.60%까지 개선되었다.
   - 그러나 HOTSPOT 환경에서 optimistic lock conflict가 폭증했다.
   - `ObjectOptimisticLockingFailureException`이 43,439건 발생했고, HOTSPOT 성공은 6,346 / 32,000에 그쳤다.
   - ADR-002 결론: Optimistic Lock은 단독 개선안으로 채택하지 않는다.

Peak Traffic Capacity Planning 기준은 다음과 같다.

| 구간 | 요청 수 | 시간 | 목표 유입률 |
| --- | ---: | ---: | ---: |
| 피크 구간 | 48,000 | 10초 | 4,800 req/s |
| 후속 구간 | 32,000 | 20초 | 1,600 req/s |
| 합계 | 80,000 | 30초 | - |

현재 동기식 API는 피크 유입을 API thread와 DB transaction이 즉시 감당해야 한다. 이 구조에서는 HOTSPOT row lock, connection pool pending, optimistic conflict 중 하나로 병목이 전이된다.

## Decision
Queue 기반 수강신청 구조를 다음 개선 후보로 설계한다.

기존 API는 유지한다.

```text
POST /api/baseline/enrollments
POST /api/optimistic/enrollments
```

Queue 실험 API는 별도 endpoint로 추가하는 방향을 채택한다.

```text
POST /api/queue/enrollments
```

이 ADR에서는 구현을 확정하지 않고, Queue 후보와 첫 번째 로컬 실험 방향을 결정한다.

## Queue 기반 구조가 필요한 이유

Queue의 목적은 DB 처리량을 갑자기 4,800 TPS로 올리는 것이 아니다.

목적은 다음과 같다.

- 피크 유입과 DB 처리 속도를 분리한다.
- API thread가 DB row lock을 오래 기다리지 않게 한다.
- HOTSPOT course에 대한 쓰기 순서를 제어한다.
- 동일 course에 대한 정원 차감과 enrollment insert를 순차화한다.
- 부하가 순간적으로 몰려도 시스템 실패를 5xx/timeout이 아니라 대기 또는 접수 상태로 흡수한다.
- baseline, optimistic과 같은 payload로 비교 가능한 세 번째 실험군을 만든다.

즉 Queue는 latency를 무조건 낮추는 장치라기보다, admission control과 write serialization을 통해 피크 부하를 안전하게 흡수하기 위한 장치다.

## Queue 설계 후보 비교

### 1. 단일 Queue

모든 수강신청 요청을 하나의 Queue에 넣고 worker가 순차 처리한다.

장점:

- 구현이 가장 단순하다.
- 처리 순서를 이해하기 쉽다.
- 동시성 문제가 가장 적다.
- 로컬 실험에서 baseline 대비 효과를 빠르게 확인할 수 있다.

단점:

- 서로 다른 course 요청까지 모두 직렬화된다.
- 전체 처리량이 worker 하나 또는 제한된 worker 수에 묶인다.
- HOTSPOT이 아닌 과목도 HOTSPOT 대기열의 영향을 받는다.

적합한 용도:

- Queue 도입 효과를 검증하는 첫 실험
- API 접수와 DB 처리 분리 효과 확인
- worker 처리량, queue depth, timeout 정책 관측

### 2. courseId 기반 partition Queue

`courseId`를 기준으로 Queue를 분리하거나 partition key로 사용한다.

장점:

- 같은 course 요청은 순차 처리하고, 서로 다른 course는 병렬 처리할 수 있다.
- HOTSPOT row lock 경합을 course 단위로 제어할 수 있다.
- 수강신청 도메인의 경합 단위와 partition key가 잘 맞는다.

단점:

- partition 수, worker 배치, courseId hashing 정책이 필요하다.
- 특정 HOTSPOT course partition이 병목이 될 수 있다.
- time conflict, prerequisite처럼 student 기준 검증이 섞이면 cross-course 일관성 검토가 필요하다.
- 운영형 구현에서는 실패 재처리, 중복 제거, idempotency 설계가 필요하다.

적합한 용도:

- 단일 Queue 이후의 확장 실험
- HOTSPOT course와 일반 course를 분리해 처리량을 비교하는 실험

### 3. Redis Stream / BlockingQueue / Kafka

| 후보 | 장점 | 단점 | 현재 프로젝트 적합성 |
| --- | --- | --- | --- |
| Java `BlockingQueue` | 외부 인프라 없음. 구현/디버깅이 단순. 로컬 실험이 빠름. | 프로세스 재시작 시 queue 유실. 다중 인스턴스 확장 불가. 운영 내구성 없음. | 첫 로컬 실험에 적합 |
| Redis Stream | consumer group, ack, pending entry 관리 가능. Redis 기반으로 비교적 가벼움. | Redis 인프라 필요. 장애/재처리/idempotency 설계 필요. | 두 번째 단계 후보 |
| Kafka | partition 기반 처리와 내구성이 강함. courseId partition 설계에 적합. | 인프라가 무겁고 운영 복잡도가 높음. 현재 baseline 실험 단계에는 과함. | 운영 확장 설계 후보 |

## Local Experiment 선택

첫 Queue 실험은 Java `BlockingQueue` 기반 단일 Queue로 시작한다.

선택 이유:

- 현재 목표는 운영용 Queue 완성이 아니라 구조적 효과 검증이다.
- 외부 인프라 없이 baseline/optimistic과 같은 Spring Boot 프로세스 안에서 빠르게 비교할 수 있다.
- Queue depth, accepted/rejected, processed/failed, processing latency 같은 지표를 먼저 정의할 수 있다.
- 단일 Queue 실험 결과를 기준으로 courseId partition Queue가 필요한지 판단할 수 있다.

단, `BlockingQueue`는 운영 해법으로 확정하지 않는다.

첫 실험의 범위:

- API request를 queue에 넣고 즉시 접수 응답을 반환한다.
- 별도 worker가 queue에서 꺼내 기존 수강신청 검증/저장 로직과 동일한 DB transaction을 수행한다.
- queue capacity를 둔다.
- queue가 가득 차면 명시적인 실패 응답을 반환한다.
- worker 처리 결과는 metric/log로 관측한다.

## Endpoint 설계

새 endpoint:

```text
POST /api/queue/enrollments
```

기존 endpoint는 유지한다.

```text
POST /api/baseline/enrollments
POST /api/optimistic/enrollments
```

k6 비교 계획:

```bash
API_MODE=baseline ...
API_MODE=optimistic ...
API_MODE=queue ...
```

`k6/baseline-enrollment.js`는 현재 `API_MODE`로 endpoint를 선택한다. Queue 구현 시 다음 mapping을 추가한다.

| API_MODE | endpoint |
| --- | --- |
| `baseline` | `/api/baseline/enrollments` |
| `optimistic` | `/api/optimistic/enrollments` |
| `queue` | `/api/queue/enrollments` |

## 응답 정책

Queue API는 동기식 baseline/optimistic API와 의미가 다르다. 요청 접수와 DB 처리 완료가 분리되기 때문이다.

초기 로컬 실험에서는 다음 정책을 우선 검토한다.

### 접수 성공

Queue에 enqueue 성공:

```http
202 Accepted
```

```json
{
  "status": "ACCEPTED"
}
```

의미:

- API가 요청을 접수했다.
- 실제 수강신청 성공은 worker 처리 결과에 달려 있다.

### Queue 포화

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

### 잘못된 요청

request validation 실패:

```http
400 Bad Request
```

기존 validation 정책을 따른다.

### Worker 처리 실패

worker 내부에서 발생하는 도메인 실패:

- `CapacityExcessException`
- `DuplicateEnrollmentException`
- `AlreadyCompletedCourseException`
- `PrerequisiteNotMetException`
- `TimeConflictException`

초기 구현에서는 API 응답으로 즉시 전달할 수 없으므로 metric/log로 기록한다.

운영형 설계에서는 request id를 반환하고 별도 조회 API 또는 SSE/WebSocket/polling으로 결과 확인이 필요하다. 이 프로젝트의 첫 로컬 실험 범위에서는 결과 조회 API는 TODO로 둔다.

## k6 비교 계획

Queue API는 202를 정상 접수로 볼 수 있어야 한다.

k6 변경 계획:

- `API_MODE=queue` 추가
- queue mode에서는 expected success status를 200이 아니라 202로 인정
- 429는 queue capacity 초과로 별도 집계
- 기존 `baseline_system_failure_rate`는 5xx/네트워크 실패 기준을 유지
- `baseline_critical_mismatch_total` 정의를 queue mode에 맞게 조정

비교 지표:

| 지표 | 의미 |
| --- | --- |
| accepted rate | 202 접수 처리량 |
| queue full count | 429 발생 수 |
| system failure rate | 5xx/네트워크 실패 |
| queue depth | 대기열 길이 |
| worker processed count | worker 처리 완료 수 |
| worker domain failure count | 정원 초과/중복/선수과목/시간표 충돌 |
| worker processing latency | enqueue부터 DB 처리 완료까지 시간 |

## 구현 전 확인해야 할 리스크

1. 응답 의미 차이
   - baseline/optimistic은 요청 처리 완료를 응답한다.
   - queue는 접수 성공을 응답한다.
   - 단순 HTTP 200/202 비교는 공정하지 않을 수 있다.

2. 결과 추적
   - Queue API가 202를 반환하면 실제 성공/실패를 별도로 추적해야 한다.
   - request id 또는 enrollment command id가 필요할 수 있다.

3. 중복 요청
   - 같은 student/course 요청이 queue에 여러 번 들어갈 수 있다.
   - enqueue 단계에서 중복을 막을지, worker DB unique constraint에 맡길지 결정해야 한다.

4. Queue capacity
   - capacity가 너무 크면 메모리와 대기 시간이 증가한다.
   - 너무 작으면 429가 과도하게 증가한다.

5. Worker 수
   - worker가 1개이면 단순하지만 처리량이 제한된다.
   - worker를 늘리면 같은 course에 대한 DB lock 경합이 다시 생길 수 있다.

6. courseId partition 전환 가능성
   - 단일 Queue가 병목이면 courseId 기반 partition Queue로 확장해야 한다.
   - 이 경우 partition 수와 worker mapping 정책이 필요하다.

7. 프로세스 장애
   - `BlockingQueue`는 메모리 기반이므로 애플리케이션 재시작 시 대기 요청이 유실된다.
   - 로컬 실험에서는 허용하되 운영 대안으로는 Redis Stream 또는 Kafka를 재검토해야 한다.

8. k6 expected_status 정책
   - 기존 payload의 `expected_status=200/400`은 즉시 처리 API 기준이다.
   - queue mode에서는 접수 응답과 worker 결과가 분리되므로 k6 검증 기준을 별도 정의해야 한다.

## Consequence

다음 구현 단계에서는 `BlockingQueue` 기반 `/api/queue/enrollments`를 별도 API로 추가한다.

기존 baseline/optimistic API는 유지한다.

Queue 구현 후 ADR-004 또는 실험 문서에서 다음을 비교한다.

- baseline
- connection pool scaling
- optimistic lock
- single in-memory queue
- courseId partition queue 후보

Queue 실험의 핵심 판정은 단순 API latency가 아니라, 피크 트래픽을 5xx/timeout 없이 접수하고 worker가 안정적으로 처리할 수 있는지다.
