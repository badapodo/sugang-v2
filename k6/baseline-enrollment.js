import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PAYLOAD_PATH = __ENV.PAYLOAD_PATH || '../mock/sugang-mock/output/csv/enrollment_payload.csv';
const MAX_DURATION = __ENV.MAX_DURATION || '45s';
const DEFAULT_ITERATIONS = 80000;
const SCENARIO_FILTER = (__ENV.SCENARIO_FILTER || '').trim();
const LIMIT = Number(__ENV.LIMIT || 0);
const REQUESTED_VUS = Number(__ENV.VUS || 200);
const EFFECTIVE_ITERATIONS = Number(__ENV.ITERATIONS || LIMIT || DEFAULT_ITERATIONS);
const EFFECTIVE_VUS = Math.max(1, Math.min(REQUESTED_VUS, EFFECTIVE_ITERATIONS));
const SAMPLE_LIMIT = 10;

const expectedStatusMismatch = new Counter('baseline_expected_status_mismatch_total');
const scenarioRequests = new Counter('baseline_scenario_requests_total');
const scenarioStatusCount = new Counter('baseline_scenario_status_count_total');
const systemFailureRate = new Rate('baseline_system_failure_rate');
const systemFailureCount = new Counter('baseline_system_failure_total');

const responseSamples = [];

export const options = {
  scenarios: {
    baseline_enrollment: {
      executor: 'shared-iterations',
      vus: EFFECTIVE_VUS,
      iterations: EFFECTIVE_ITERATIONS,
      maxDuration: MAX_DURATION,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
    baseline_system_failure_rate: ['rate<0.005'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const payloads = new SharedArray('enrollment payloads', () => {
  const raw = open(PAYLOAD_PATH).trim();
  const [headerLine, ...lines] = raw.split(/\r?\n/);
  const headers = headerLine.split(',');

  let rows = lines.map((line) => {
    const values = line.split(',');
    return Object.fromEntries(headers.map((header, index) => [header, values[index]]));
  });

  if (SCENARIO_FILTER) {
    const allowed = new Set(
      SCENARIO_FILTER
        .split(',')
        .map((scenario) => scenario.trim())
        .filter(Boolean)
    );
    rows = rows.filter((row) => allowed.has(row.scenario_type));
  }

  if (LIMIT > 0) {
    rows = rows.slice(0, LIMIT);
  }

  return rows;
});

const startedAt = Date.now();

export default function () {
  if (payloads.length === 0) {
    fail(`No payload rows loaded from ${PAYLOAD_PATH}. SCENARIO_FILTER=${SCENARIO_FILTER || '-'}`);
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
  scenarioStatusCount.add(1, {
    scenario_type: row.scenario_type,
    status: String(response.status),
  });

  const statusMatched = isExpectedStatus(row.expected_status, response.status);
  const systemFailure = Boolean(response.error) || response.status === 0 || response.status >= 500;

  systemFailureRate.add(systemFailure, { scenario_type: row.scenario_type });

  if (!statusMatched) {
    expectedStatusMismatch.add(1, {
      scenario_type: row.scenario_type,
      expected_status: row.expected_status,
      actual_status: String(response.status),
    });
    addResponseSample('EXPECTED_STATUS_MISMATCH', row, response);
  }

  if (systemFailure) {
    systemFailureCount.add(1, {
      scenario_type: row.scenario_type,
      status: String(response.status),
    });
    addResponseSample('SYSTEM_FAILURE', row, response);
  }

  check(response, {
    'expected status matched': () => statusMatched,
    'success payload returns exactly 200': () => row.expected_status !== '200' || response.status === 200,
    'domain failure payload returns 4xx': () => row.expected_status !== '400' || (response.status >= 400 && response.status < 500),
    'no system failure': () => !systemFailure,
  });
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
  };
}

function isExpectedStatus(expectedStatus, actualStatus) {
  if (expectedStatus === '200') {
    return actualStatus === 200;
  }

  if (expectedStatus === '400') {
    return actualStatus >= 400 && actualStatus < 500;
  }

  return actualStatus === Number(expectedStatus);
}

function textSummary(data) {
  const metrics = data.metrics || {};
  const duration = metrics.http_req_duration?.values || {};
  const requests = metrics.http_reqs?.values || {};
  const mismatch = metrics.baseline_expected_status_mismatch_total?.values || {};
  const systemFailure = metrics.baseline_system_failure_rate?.values || {};
  const systemFailureTotal = metrics.baseline_system_failure_total?.values || {};

  return [
    '',
    'Baseline enrollment load test summary',
    `- scenario filter: ${SCENARIO_FILTER || 'ALL'}`,
    `- payload limit: ${LIMIT || 'ALL'}`,
    `- effective iterations: ${EFFECTIVE_ITERATIONS}`,
    `- requested VUs: ${REQUESTED_VUS}`,
    `- effective VUs: ${EFFECTIVE_VUS}`,
    `- requests: ${requests.count ?? 0}`,
    `- request rate: ${formatNumber(requests.rate)} req/s`,
    `- system failure rate: ${formatNumber((systemFailure.rate ?? 0) * 100)}%`,
    `- system failure count: ${systemFailureTotal.count ?? 0}`,
    `- latency p95: ${formatNumber(duration['p(95)'])} ms`,
    `- latency p99: ${formatNumber(duration['p(99)'])} ms`,
    `- expected status mismatches: ${mismatch.count ?? 0}`,
    '',
    'Scenario status count',
    formatScenarioStatusCounts(metrics),
    '',
    'Response body samples',
    formatResponseSamples(),
    '',
  ].join('\n');
}

function addResponseSample(type, row, response) {
  if (responseSamples.length >= SAMPLE_LIMIT) {
    return;
  }

  responseSamples.push({
    type,
    scenario_type: row.scenario_type,
    student_id: row.student_id,
    course_id: row.course_id,
    expected_status: row.expected_status,
    actual_status: response.status,
    error: response.error || '',
    body: sanitizeBody(response.body),
  });
}

function sanitizeBody(body) {
  if (!body) {
    return '';
  }

  const normalized = String(body).replace(/\s+/g, ' ').trim();
  return normalized.length > 500 ? `${normalized.slice(0, 500)}...` : normalized;
}

function formatScenarioStatusCounts(metrics) {
  const rows = Object.entries(metrics)
    .filter(([name]) => name.startsWith('baseline_scenario_status_count_total{'))
    .map(([name, metric]) => ({
      scenario: extractTag(name, 'scenario_type') || 'unknown',
      status: extractTag(name, 'status') || 'unknown',
      count: metric.values?.count ?? 0,
    }))
    .sort((a, b) => a.scenario.localeCompare(b.scenario) || a.status.localeCompare(b.status));

  if (rows.length === 0) {
    return '- no scenario status count metrics collected';
  }

  return rows.map((row) => `- ${row.scenario} / HTTP ${row.status}: ${row.count}`).join('\n');
}

function extractTag(metricName, tagName) {
  const match = metricName.match(new RegExp(`${tagName}:([^,}]+)`));
  return match ? match[1] : '';
}

function formatResponseSamples() {
  if (responseSamples.length === 0) {
    return '- no mismatch or system failure samples';
  }

  return responseSamples
    .slice(0, SAMPLE_LIMIT)
    .map((sample, index) => [
      `Sample ${index + 1}`,
      `  type: ${sample.type}`,
      `  scenario_type: ${sample.scenario_type}`,
      `  student_id: ${sample.student_id}`,
      `  course_id: ${sample.course_id}`,
      `  expected_status: ${sample.expected_status}`,
      `  actual_status: ${sample.actual_status}`,
      `  error: ${sample.error || '-'}`,
      `  body: ${sample.body || '-'}`,
    ].join('\n'))
    .join('\n');
}

function formatNumber(value) {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '0';
  }
  return Number(value).toFixed(2);
}
