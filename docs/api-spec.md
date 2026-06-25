# API Specification

## 개요

Baseline 단계의 API는 수강신청 성능 병목을 측정하기 위한 단일 엔드포인트만 제공한다.

인증, Redis, MQ, 비동기 큐는 제외하고 `studentId`, `courseId`를 직접 받아 동기식 DB 트랜잭션으로 처리한다.

## POST /api/baseline/enrollments

수강신청 요청을 즉시 처리한다.

### Request

```http
POST /api/baseline/enrollments
Content-Type: application/json
```

```json
{
  "studentId": 1001,
  "courseId": 20
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `studentId` | number | Y | 신청 학생 ID. Mock Harness의 `student.csv` 기준 |
| `courseId` | number | Y | 신청 과목 ID. Mock Harness의 `course.csv` 기준 |

### Success Response

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "status": "SUCCESS"
}
```

### Failure Response

도메인 검증 실패는 4xx 응답으로 반환한다.

```json
{
  "status": "FAIL",
  "reason": "DuplicateEnrollmentException",
  "message": "현재 학기에 이미 신청한 과목입니다."
}
```

### Failure Cases

| HTTP Status | `reason` | 의미 |
| --- | --- | --- |
| 400 | `PrerequisiteNotMetException` | 선수과목을 이수하지 않음 |
| 400 | `INVALID_REQUEST` | 필수 요청 필드 누락 |
| 409 | `CapacityExcessException` | 과목 정원 초과 |
| 409 | `DuplicateEnrollmentException` | 현재 학기 동일 과목 중복 신청 |
| 409 | `AlreadyCompletedCourseException` | 이미 이수한 과목 재신청 |
| 409 | `TimeConflictException` | 기존 신청 과목과 시간표 충돌 |

## k6 Payload Mapping

부하 테스트는 Mock Harness의 payload를 그대로 사용한다.

```text
mock/sugang-mock/output/csv/enrollment_payload.csv
```

CSV 컬럼과 API 매핑:

| CSV 컬럼 | API 필드/용도 |
| --- | --- |
| `student_id` | request body `studentId` |
| `course_id` | request body `courseId` |
| `scenario_type` | k6 tag `scenario_type` |
| `expected_status` | k6 check 기준 |
| `scheduled_offset_ms` | 요청 예약 실행 시각 |

## k6 검증 기준

`k6/baseline-enrollment.js`는 다음을 확인한다.

| Payload expected_status | 기대 API 결과 |
| --- | --- |
| `200` | HTTP 200 |
| `400` | HTTP 4xx |

Baseline에서는 실패 reason의 완전한 매칭보다 다음을 우선한다.

- 성공 payload가 실제로 성공하는가
- 실패 payload가 서버 오류가 아니라 도메인 실패로 방어되는가
- 5xx 비율이 증가하는 지점은 어디인가

## Example

```bash
curl -X POST http://localhost:8080/api/baseline/enrollments \
  -H 'Content-Type: application/json' \
  -d '{"studentId":1001,"courseId":20}'
```

## 관련 코드

| 책임 | 파일 |
| --- | --- |
| API Controller | `src/main/java/badapodo/sugang/controller/BaselineEnrollmentController.java` |
| Request DTO | `src/main/java/badapodo/sugang/controller/request/BaselineEnrollmentRequest.java` |
| Response DTO | `src/main/java/badapodo/sugang/controller/response/BaselineEnrollmentResponse.java` |
| Error Response | `src/main/java/badapodo/sugang/response/ErrorResponse.java` |
| Exception Handler | `src/main/java/badapodo/sugang/exception/GlobalExceptionHandler.java` |
