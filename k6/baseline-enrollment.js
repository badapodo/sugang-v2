import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PAYLOAD_PATH = __ENV.PAYLOAD_PATH || '../mock/sugang-mock/output/csv/enrollment_payload.csv';
const MAX_DURATION = __ENV.MAX_DURATION || '45s';

export const options = {
  scenarios: {
    baseline_enrollment: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 200),
      iterations: Number(__ENV.ITERATIONS || 80000),
      maxDuration: MAX_DURATION,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
  },
};

const payloads = new SharedArray('enrollment payloads', () => {
  const raw = open(PAYLOAD_PATH).trim();
  const [headerLine, ...lines] = raw.split(/\r?\n/);
  const headers = headerLine.split(',');

  return lines.map((line) => {
    const values = line.split(',');
    return Object.fromEntries(headers.map((header, index) => [header, values[index]]));
  });
});

const startedAt = Date.now();

export default function () {
  const row = payloads[__ITER % payloads.length];
  const scheduledOffsetMs = Number(row.scheduled_offset_ms || 0);
  const waitMs = startedAt + scheduledOffsetMs - Date.now();

  if (waitMs > 0) {
    sleep(waitMs / 1000);
  }

  const response = http.post(
    `${BASE_URL}/api/baseline/enrollments`,
    JSON.stringify({
      studentId: Number(row.student_id),
      courseId: Number(row.course_id),
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Scenario-Type': row.scenario_type,
      },
      tags: {
        scenario_type: row.scenario_type,
        expected_status: row.expected_status,
      },
    }
  );

  check(response, {
    'success payload returns 200': () => row.expected_status !== '200' || response.status === 200,
    'failure payload returns 4xx': () => row.expected_status === '200' || (response.status >= 400 && response.status < 500),
    'no server error': () => response.status < 500,
  });
}
