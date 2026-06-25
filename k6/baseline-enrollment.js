import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PAYLOAD_PATH = __ENV.PAYLOAD_PATH || '../mock/sugang-mock/output/csv/enrollment_payload.csv';
const MAX_DURATION = __ENV.MAX_DURATION || '45s';
const DEFAULT_ITERATIONS = 80000;

const expectedStatusMismatch = new Counter('baseline_expected_status_mismatch_total');
const scenarioRequests = new Counter('baseline_scenario_requests_total');

export const options = {
  scenarios: {
    baseline_enrollment: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 200),
      iterations: Number(__ENV.ITERATIONS || DEFAULT_ITERATIONS),
      maxDuration: MAX_DURATION,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
    baseline_expected_status_mismatch_total: ['count<1'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
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
  if (payloads.length === 0) {
    fail(`No payload rows loaded from ${PAYLOAD_PATH}`);
  }

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

  scenarioRequests.add(1, { scenario_type: row.scenario_type });

  const statusMatched = row.expected_status === '200'
    ? response.status === 200
    : response.status >= 400 && response.status < 500;

  if (!statusMatched) {
    expectedStatusMismatch.add(1, {
      scenario_type: row.scenario_type,
      expected_status: row.expected_status,
      actual_status: String(response.status),
    });
  }

  check(response, {
    'expected status matched': () => statusMatched,
    'success payload returns 200': () => row.expected_status !== '200' || response.status === 200,
    'failure payload returns 4xx': () => row.expected_status === '200' || (response.status >= 400 && response.status < 500),
    'no server error': () => response.status < 500,
  });
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const metrics = data.metrics || {};
  const duration = metrics.http_req_duration?.values || {};
  const requests = metrics.http_reqs?.values || {};
  const failed = metrics.http_req_failed?.values || {};
  const mismatch = metrics.baseline_expected_status_mismatch_total?.values || {};

  return [
    '',
    'Baseline enrollment load test summary',
    `- requests: ${requests.count ?? 0}`,
    `- request rate: ${formatNumber(requests.rate)} req/s`,
    `- http_req_failed: ${formatNumber((failed.rate ?? 0) * 100)}%`,
    `- latency p95: ${formatNumber(duration['p(95)'])} ms`,
    `- latency p99: ${formatNumber(duration['p(99)'])} ms`,
    `- expected status mismatches: ${mismatch.count ?? 0}`,
    '',
  ].join('\n');
}

function formatNumber(value) {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '0';
  }
  return Number(value).toFixed(2);
}
