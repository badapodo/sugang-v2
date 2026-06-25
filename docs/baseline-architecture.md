# Baseline Architecture

## 목적

이 프로젝트의 Baseline은 Redis, MQ, 비동기 대기열 없이 `Spring Boot API → PostgreSQL` 구조만으로 수강신청 요청을 즉시 처리한다.

목표는 최적화가 아니라 한계 측정이다. Hotspot 과목에 요청이 몰릴 때 동기식 WAS-RDB 구조가 어디서 병목을 만드는지 관찰하고, 이후 Redis Lock, Queue, MQ 도입의 근거를 만들기 위한 기준선이다.

## 구조

```text
k6
  → POST /api/baseline/enrollments
  → Spring Boot
  → JPA Transaction
  → PostgreSQL SELECT ... FOR UPDATE
```

## 제외한 것

- Redis
- Redisson Distributed Lock
- Kafka / RabbitMQ
- 비동기 대기열
- JWT 인증 병목

Baseline에서는 인증/캐시/큐를 제거하고 DB 트랜잭션과 Row Lock만 관찰한다.

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
  "reason": "CapacityExcessException",
  "message": "정원이 초과되었습니다."
}
```

## 트랜잭션 순서

```text
1. Course SELECT ... FOR UPDATE
2. 정원 확인
3. 중복 신청 확인
4. 선수과목 확인
5. 시간표 충돌 확인
6. Enrollment 저장
7. Course.currentCount 증가
```

Hotspot 과목에서는 1번 단계의 course row lock에서 의도적으로 경합이 발생한다.

## 측정 지표

| 계층 | 지표 |
| --- | --- |
| k6 | TPS, http_req_duration, error rate, scenario_type별 실패율 |
| Spring | request latency, exception count |
| HikariCP | active connections, pending threads, timeout count |
| PostgreSQL | lock wait, connection count, slow query |

## 성공 기준

Baseline 테스트의 목적은 모든 지표를 좋게 만드는 것이 아니라 다음을 확인하는 것이다.

- 정원 초과 저장 0건
- 동일 학생/과목 중복 저장 0건
- 선수과목 실패 payload가 4xx로 방어됨
- 시간표 충돌 payload가 4xx로 방어됨
- Hotspot에서 P95/P99, TPS, lock wait 병목이 관찰됨

## 실행 흐름

```bash
docker compose up -d postgres
psql -h localhost -U user -d enrollment -f infra/postgres/schema.sql
psql -h localhost -U user -d enrollment -f infra/postgres/load.sql
./gradlew bootRun
k6 run k6/baseline-enrollment.js
```

반복 테스트 전 초기화:

```bash
psql -h localhost -U user -d enrollment -f infra/postgres/reset.sql
```
