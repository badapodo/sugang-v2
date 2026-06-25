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
const IGNORE_SCHEDULE = (__ENV.IGNORE_SCHEDULE || 'false').toLowerCase() === 'true';
const SAMPLE_LIMIT = 20;

const SCENARIOS = ['NORMAL', 'PREREQUISITE_FAIL', 'TIME_CONFLICT', 'CAPACITY_OVER', 'DUPLICATE'];
const STATUS_BUCKETS = ['200', '400', '409', '500'];

const strictExpectedStatusMismatch = new Counter('baseline_strict_expected_status_mismatch_total');
const criticalMismatch = new Counter('baseline_critical_mismatch_total');
const scenarioRequests = new Counter('baseline_scenario_requests_total');
const systemFailureRate = new Rate('baseline_system_failure_rate');
const systemFailureCount = new Counter('baseline_system_failure_total');
const mismatchSampleMetric = new Counter('baseline_mismatch_sample_total');

const statusCounters = createScenarioStatusCounters();
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
    baseline_critical_mismatch_total: ['count<1'],
    baseline_system_failure_rate: ['rate<0.005'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const payloads = new SharedArray('enrollment payloads', () => {
  const raw = open(PAYLOAD_PATH).trim();
  const [headerLine, ...lines] = raw.split(/\r?\n/);
  const headers = headerLine.split(',');

  let rows = lines.map((line, index) => {
    const values = line.split(',');
    return {
      request_id: index + 1,
      ...Object.fromEntries(headers.map((header, valueIndex) => [header, values[valueIndex]])),
    };
  });

  rows = rows.sort((left, right) => {
    const leftOffset = Number(left.scheduled_offset_ms || 0);
    const rightOffset = Number(right.scheduled_offset_ms || 0);
    return leftOffset - rightOffset;
  });

  if (SCENARIO_FILTER && SCENARIO_FILTER.toUpperCase() !== 'ALL') {
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

  if (!IGNORE_SCHEDULE) {
    const scheduledOffsetMs = Number(row.scheduled_offset_ms || 0);
    const targetTime = startedAt + scheduledOffsetMs;
    const waitMs = targetTime - Date.now();

    if (waitMs > 0) {
      sleep(waitMs / 1000);
    }
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

  const actualStatus = response.status || 0;
  const statusBucket = toStatusBucket(actualStatus);
  const strictMismatch = isStrictMismatch(row.expected_status, actualStatus);
  const systemFailure = Boolean(response.error) || actualStatus === 0 || actualStatus >= 500;
  const critical = isCriticalMismatch(row.expected_status, actualStatus, systemFailure);

  scenarioRequests.add(1, { scenario_type: row.scenario_type });
  addScenarioStatus(row.scenario_type, statusBucket);
  systemFailureRate.add(systemFailure, { scenario_type: row.scenario_type });

  if (strictMismatch) {
    strictExpectedStatusMismatch.add(1, {
      scenario_type: row.scenario_type,
      expected_status: row.expected_status,
      actual_status: String(actualStatus),
    });
    addMismatchSample(row, response);
  }

  if (critical) {
    criticalMismatch.add(1, {
      scenario_type: row.scenario_type,
      expected_status: row.expected_status,
      actual_status: String(actualStatus),
    });
  }

  if (systemFailure) {
    systemFailureCount.add(1, {
      scenario_type: row.scenario_type,
      status: String(actualStatus),
    });
  }

  check(response, {
    'strict expected status matched': () => !strictMismatch,
    'no critical mismatch': () => !critical,
    'no system failure': () => !systemFailure,
  });
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
  };
}

function createScenarioStatusCounters() {
  const counters = {};

  for (const scenario of SCENARIOS) {
    counters[scenario] = {};
    for (const status of STATUS_BUCKETS) {
      counters[scenario][status] = new Counter(metricNameForStatus(scenario, status));
    }
  }

  return counters;
}

function addScenarioStatus(scenarioType, statusBucket) {
  if (!statusCounters[scenarioType]) {
    return;
  }

  if (!statusCounters[scenarioType][statusBucket]) {
    return;
  }

  statusCounters[scenarioType][statusBucket].add(1);
}

function metricNameForStatus(scenarioType, statusBucket) {
  return `baseline_status_${normalizeMetricPart(scenarioType)}_${statusBucket}_total`;
}

function normalizeMetricPart(value) {
  return String(value).toLowerCase().replace(/[^a-z0-9]+/g, '_');
}

function toStatusBucket(actualStatus) {
  if (actualStatus === 200) {
    return '200';
  }

  if (actualStatus === 409) {
    return '409';
  }

  if (actualStatus >= 500 || actualStatus === 0) {
    return '500';
  }

  if (actualStatus >= 400 && actualStatus < 500) {
    return '400';
  }

  return '500';
}

function isStrictMismatch(expectedStatus, actualStatus) {
  if (expectedStatus === '200') {
    return actualStatus !== 200;
  }

  if (expectedStatus === '400') {
    return actualStatus < 400 || actualStatus >= 500;
  }

  return actualStatus !== Number(expectedStatus);
}

function isCriticalMismatch(expectedStatus, actualStatus, systemFailure) {
  if (systemFailure) {
    return true;
  }

  if (expectedStatus === '400' && actualStatus === 200) {
    return true;
  }

  return false;
}

function textSummary(data) {
  const metrics = data.metrics || {};
  const duration = metrics.http_req_duration?.values || {};
  const requests = metrics.http_reqs?.values || {};
  const strictMismatch = metrics.baseline_strict_expected_status_mismatch_total?.values || {};
  const critical = metrics.baseline_critical_mismatch_total?.values || {};
  const systemFailure = metrics.baseline_system_failure_rate?.values || {};
  const systemFailureTotal = metrics.baseline_system_failure_total?.values || {};

  return [
    '',
    'Baseline enrollment load test summary',
    `- scenario filter: ${SCENARIO_FILTER || 'ALL'}`,
    `- payload limit: ${LIMIT || 'ALL'}`,
    `- ignore schedule: ${IGNORE_SCHEDULE}`,
    `- effective iterations: ${EFFECTIVE_ITERATIONS}`,
    `- requested VUs: ${REQUESTED_VUS}`,
    `- effective VUs: ${EFFECTIVE_VUS}`,
    `- requests: ${requests.count ?? 0}`,
    `- request rate: ${formatNumber(requests.rate)} req/s`,
    `- strict mismatch count: ${strictMismatch.count ?? 0}`,
    `- critical mismatch count: ${critical.count ?? 0}`,
    `- system failure rate: ${formatNumber((systemFailure.rate ?? 0) * 100)}%`,
    `- system failure count: ${systemFailureTotal.count ?? 0}`,
    `- latency p95: ${formatNumber(duration['p(95)'])} ms`,
    `- latency p99: ${formatNumber(duration['p(99)'])} ms`,
    '',
    'Scenario status count',
    formatScenarioStatusCounts(metrics),
    '',
    'Mismatch samples',
    formatMismatchSamples(metrics),
    '',
  ].join('\n');
}

function addMismatchSample(row, response) {
  if (responseSamples.length >= SAMPLE_LIMIT) {
    return;
  }

  const sample = {
    request_id: row.request_id,
    scenario_type: row.scenario_type,
    expected_status: row.expected_status,
    actual_status: response.status || 0,
    student_id: row.student_id,
    course_id: row.course_id,
    response_body: sanitizeBody(response.body),
  };

  responseSamples.push(sample);
  mismatchSampleMetric.add(1, {
    request_id: String(sample.request_id),
    scenario_type: sample.scenario_type,
    expected_status: sample.expected_status,
    actual_status: String(sample.actual_status),
    student_id: String(sample.student_id),
    course_id: String(sample.course_id),
    response_body: encodeURIComponent(sample.response_body.slice(0, 200)),
  });

  // k6 does not reliably aggregate arbitrary mutable JS arrays from multiple VUs
  // into handleSummary. Logging the same compact sample guarantees visibility
  // during smoke tests and preserves the summary path when VU-local data is available.
  console.error(`MISMATCH_SAMPLE ${JSON.stringify(sample)}`);
}

function sanitizeBody(body) {
  if (!body) {
    return '';
  }

  const normalized = String(body).replace(/\s+/g, ' ').trim();
  return normalized.length > 500 ? `${normalized.slice(0, 500)}...` : normalized;
}

function formatScenarioStatusCounts(metrics) {
  return SCENARIOS
    .map((scenario) => {
      const counts = STATUS_BUCKETS
        .map((status) => `${status}=${metricCount(metrics, metricNameForStatus(scenario, status))}`)
        .join(', ');

      return `- ${scenario}: ${counts}`;
    })
    .join('\n');
}

function metricCount(metrics, metricName) {
  return metrics[metricName]?.values?.count ?? 0;
}

function formatMismatchSamples(metrics) {
  if (responseSamples.length > 0) {
    return responseSamples
      .slice(0, SAMPLE_LIMIT)
      .map((sample, index) => formatSample(sample, index))
      .join('\n');
  }

  const metricSamples = formatMismatchSamplesFromMetrics(metrics);
  if (metricSamples) {
    return metricSamples;
  }

  return '- no strict mismatch samples collected in summary. Check MISMATCH_SAMPLE lines in k6 output if running with multiple VUs.';
}

function formatSample(sample, index) {
  return [
    `Sample ${index + 1}`,
    `  request_id: ${sample.request_id}`,
    `  scenario_type: ${sample.scenario_type}`,
    `  expected_status: ${sample.expected_status}`,
    `  actual_status: ${sample.actual_status}`,
    `  student_id: ${sample.student_id}`,
    `  course_id: ${sample.course_id}`,
    `  response_body: ${sample.response_body || '-'}`,
  ].join('\n');
}

function formatMismatchSamplesFromMetrics(metrics) {
  const samples = Object.entries(metrics)
    .filter(([name]) => name.startsWith('baseline_mismatch_sample_total{'))
    .slice(0, SAMPLE_LIMIT)
    .map(([name]) => ({
      request_id: extractTag(name, 'request_id'),
      scenario_type: extractTag(name, 'scenario_type'),
      expected_status: extractTag(name, 'expected_status'),
      actual_status: extractTag(name, 'actual_status'),
      student_id: extractTag(name, 'student_id'),
      course_id: extractTag(name, 'course_id'),
      response_body: decodeTag(extractTag(name, 'response_body')),
    }));

  if (samples.length === 0) {
    return '';
  }

  return samples
    .map((sample, index) => formatSample(sample, index))
    .join('\n');
}

function extractTag(metricName, tagName) {
  const colonStyle = metricName.match(new RegExp(`${tagName}:([^,}]+)`));
  if (colonStyle) {
    return colonStyle[1];
  }

  const equalsStyle = metricName.match(new RegExp(`${tagName}="([^"]*)"`));
  return equalsStyle ? equalsStyle[1] : '';
}

function decodeTag(value) {
  if (!value) {
    return '';
  }

  try {
    return decodeURIComponent(value);
  } catch (error) {
    return value;
  }
}

function formatNumber(value) {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '0';
  }
  return Number(value).toFixed(2);
}
