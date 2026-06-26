# ADR-001. Connection Pool 증가는 근본 해결책인가?

## Context
Peak arrival-rate 테스트에서 HikariCP pending과 PostgreSQL lock wait가 함께 증가했다.

## Decision
Connection Pool 크기 증가는 단독 개선안으로 채택하지 않는다.

## Evidence
Pool 16 대비 Pool 32에서 처리량은 소폭 증가했지만,
system failure rate는 여전히 50% 수준이었고 lock wait session은 증가했다.

## Consequence
다음 개선 후보는 Lock 경합 자체를 줄이는 방향으로 검토한다.


| 항목                  |      Pool 16 |      Pool 32 |
| ------------------- | -----------: | -----------: |
| request rate        | 486.28 req/s | 501.16 req/s |
| dropped iterations  |       50,712 |       49,772 |
| system failure rate |       51.55% |       50.17% |
| p95                 |     10,321ms |      9,491ms |
| p99                 |     10,484ms |      9,643ms |
| lock wait sessions  |      약 14~15 |         약 31 |
| Hikari pending      |        약 180 |        약 170 |
