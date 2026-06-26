# ADR-002. Optimistic Lock은 Peak Traffic 개선안으로 적합한가?

## 결론
Optimistic Lock은 latency와 system failure를 크게 줄였지만,
HOTSPOT 환경에서 optimistic lock conflict가 폭증하여
정상 성공해야 하는 요청이 대량으로 409 처리되었다.
따라서 단독 개선안으로 채택하지 않는다.

## 근거
- request rate: 500 → 1,724 req/s
- p99: 약 10초 → 230ms
- system failure: 약 50% → 1.60%
- lock wait: 제거
- 하지만 ObjectOptimisticLockingFailureException: 43,439건
- HOTSPOT 성공: 6,346 / 32,000

| 항목                  | Pessimistic Baseline |               Optimistic |
| ------------------- | -------------------: | -----------------------: |
| requests            |      약 29,000~30,000 |                   74,315 |
| dropped iterations  |             약 50,000 |                    5,683 |
| request rate        |          약 500 req/s |              1,724 req/s |
| system failure rate |                약 50% |                    1.60% |
| p95                 |              약 9~10초 |                   15.7ms |
| p99                 |              약 9~10초 |                    230ms |
| lock wait           |                   높음 |                        0 |
| 주요 실패               |          timeout/500 | Optimistic lock conflict |

- requests: 74,315
- dropped iterations: 5,683
- request rate: 1,724.19 req/s
- system failure rate: 1.60%
- p95: 15.70ms
- p99: 230.22ms
- ObjectOptimisticLockingFailureException: 43,439
- HOTSPOT: 200=6,346, 409=25,654