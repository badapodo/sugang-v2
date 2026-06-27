import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_MODE = (__ENV.API_MODE || 'baseline').trim().toLowerCase();
const BASE_PATH = normalizeBasePath(__ENV.BASE_PATH || pathForApiMode(API_MODE));
const PAYLOAD_PATH = __ENV.PAYLOAD_PATH || '../mock/sugang-mock/output/csv/enrollment_payload.csv';
const MAX_DURATION = __ENV.MAX_DURATION || '45s';
const SCENARIO_FILTER = (__ENV.SCENARIO_FILTER || '').trim();
const LIMIT = Number(__ENV.LIMIT || 0);
const REQUESTED_VUS = Number(__ENV.VUS || 200);
const REQUESTED_ITERATIONS = Number(__ENV.ITERATIONS || 0);
const IGNORE_SCHEDULE = (__ENV.IGNORE_SCHEDULE || 'false').toLowerCase() === 'true';
const EXECUTOR_MODE = (__ENV.EXECUTOR_MODE || 'shared-iterations').trim().toLowerCase();
const PEAK_RATE = Number(__ENV.PEAK_RATE || 4800);
const PEAK_DURATION = __ENV.PEAK_DURATION || '10s';
const TAIL_RATE = Number(__ENV.TAIL_RATE || 1600);
const TAIL_DURATION = __ENV.TAIL_DURATION || '20s';
const ARRIVAL_TIME_UNIT = __ENV.ARRIVAL_TIME_UNIT || '1s';
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || REQUESTED_VUS);
const MAX_VUS = Number(__ENV.MAX_VUS || Math.max(PRE_ALLOCATED_VUS, REQUESTED_VUS));
const SAMPLE_LIMIT = 20;

const SCENARIOS = ['NORMAL', 'HOTSPOT', 'PREREQUISITE_FAIL', 'TIME_CONFLICT', 'CAPACITY_OVER', 'DUPLICATE'];
const STATUS_BUCKETS = ['0', '200', '202', '400', '409', '429', '5xx'];
const REQUIRED_COLUMNS = ['student_id', 'course_id', 'scenario_type', 'expected_status', 'scheduled_offset_ms'];

const strictExpectedStatusMismatch = new Counter('baseline_strict_expected_status_mismatch_total');
const criticalMismatch = new Counter('baseline_critical_mismatch_total');
const scenarioRequests = new Counter('baseline_scenario_requests_total');
const systemFailureRate = new Rate('baseline_system_failure_rate');
const systemFailureCount = new Counter('baseline_system_failure_total');
const statusZeroCount = new Counter('baseline_status_zero_total');
const realFiveXxCount = new Counter('baseline_real_5xx_total');
const k6ErrorCount = new Counter('baseline_k6_error_total');
const acceptedCount = new Counter('baseline_accepted_total');
const queueFullCount = new Counter('baseline_queue_full_total');
const mismatchSampleMetric = new Counter('baseline_mismatch_sample_total');
const payloadStructureInvalid = new Counter('baseline_payload_structure_invalid_total');

const statusCounters = createScenarioStatusCounters();
const reasonCounters = createReasonCounters();
const responseSamples = [];
const statusZeroSamples = [];

const payloads = new SharedArray('enrollment payloads', () => {
  return loadPayloadRows();
});

const payloadMetadata = new SharedArray('enrollment payload metadata', () => {
  const rows = loadPayloadRows();
  const metadata = {
    row_count: rows.length,
    scenario_distribution: countValues(rows, 'scenario_type', SCENARIOS),
    expected_status_distribution: countValues(rows, 'expected_status', ['200', '400', '409']),
  };
  return [metadata];
});

const PAYLOAD_METADATA = payloadMetadata[0];
const PAYLOAD_SCENARIO_DISTRIBUTION = PAYLOAD_METADATA.scenario_distribution;
const PAYLOAD_EXPECTED_STATUS_DISTRIBUTION = PAYLOAD_METADATA.expected_status_distribution;
validateLoadedPayloadDistribution(PAYLOAD_SCENARIO_DISTRIBUTION, PAYLOAD_EXPECTED_STATUS_DISTRIBUTION, PAYLOAD_METADATA.row_count);
validateApiMode();

const EFFECTIVE_ITERATIONS = REQUESTED_ITERATIONS || payloads.length;
const EFFECTIVE_VUS = Math.max(1, Math.min(REQUESTED_VUS, EFFECTIVE_ITERATIONS));
const PEAK_DURATION_SECONDS = durationToSeconds(PEAK_DURATION);
const TAIL_DURATION_SECONDS = durationToSeconds(TAIL_DURATION);
const PEAK_ITERATIONS = Math.round(PEAK_RATE * PEAK_DURATION_SECONDS);
const TAIL_ITERATIONS = Math.round(TAIL_RATE * TAIL_DURATION_SECONDS);
const ARRIVAL_ITERATIONS = PEAK_ITERATIONS + TAIL_ITERATIONS;
const IS_PEAK_ARRIVAL_RATE = EXECUTOR_MODE === 'peak-arrival-rate';

validateExecutorMode();

export const options = {
  scenarios: buildScenarios(),
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
    dropped_iterations: ['count<1'],
    baseline_critical_mismatch_total: ['count<1'],
    baseline_system_failure_rate: ['rate<0.005'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const startedAt = Date.now();

export default function () {
  runSharedIteration();
}

export function peakArrival() {
  runArrivalIteration(0, PEAK_ITERATIONS);
}

export function tailArrival() {
  runArrivalIteration(PEAK_ITERATIONS, TAIL_ITERATIONS);
}

function runSharedIteration() {
  if (payloads.length === 0) {
    fail(`No payload rows loaded from ${PAYLOAD_PATH}. SCENARIO_FILTER=${SCENARIO_FILTER || '-'}`);
  }

  const iterationIndex = exec.scenario.iterationInTest;
  const row = payloads[iterationIndex % payloads.length];
  runEnrollment(row);
}

function runArrivalIteration(offset, plannedIterations) {
  if (payloads.length === 0) {
    fail(`No payload rows loaded from ${PAYLOAD_PATH}. SCENARIO_FILTER=${SCENARIO_FILTER || '-'}`);
  }

  const scenarioIteration = exec.scenario.iterationInTest;
  // constant-arrival-rate can schedule one boundary iteration beyond the
  // mathematical rate * duration count. Do not wrap and replay payload rows.
  if (scenarioIteration >= plannedIterations) {
    return;
  }

  const iterationIndex = offset + scenarioIteration;
  const row = payloads[iterationIndex];
  runEnrollment(row);
}

function runEnrollment(row) {
  if (!IGNORE_SCHEDULE && !IS_PEAK_ARRIVAL_RATE) {
    const scheduledOffsetMs = Number(row.scheduled_offset_ms || 0);
    const targetTime = startedAt + scheduledOffsetMs;
    const waitMs = targetTime - Date.now();

    if (waitMs > 0) {
      sleep(waitMs / 1000);
    }
  }

  const response = http.post(
    `${BASE_URL}${BASE_PATH}`,
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
  const reason = extractReason(response);
  const k6Error = extractK6Error(response);
  const accepted = isAccepted(actualStatus);
  const queueFull = isQueueFull(actualStatus);
  const strictMismatch = isStrictMismatch(row.expected_status, actualStatus);
  const systemFailure = Boolean(response.error) || actualStatus === 0 || actualStatus >= 500;
  const critical = isCriticalMismatch(row.expected_status, actualStatus, systemFailure);

  scenarioRequests.add(1, { scenario_type: row.scenario_type });
  addScenarioStatus(row.scenario_type, statusBucket);
  addReason(reason, row.scenario_type);
  systemFailureRate.add(systemFailure, { scenario_type: row.scenario_type });

  if (actualStatus === 0) {
    statusZeroCount.add(1, { scenario_type: row.scenario_type });
    addStatusZeroSample(row, response, k6Error);
  }

  if (actualStatus >= 500) {
    realFiveXxCount.add(1, {
      scenario_type: row.scenario_type,
      status: String(actualStatus),
    });
  }

  if (actualStatus === 0 || response.error) {
    k6ErrorCount.add(1, {
      scenario_type: row.scenario_type,
      error_code: k6Error.error_code || 'none',
    });
    addK6ErrorReason(response, k6Error);
  }

  if (accepted) {
    acceptedCount.add(1, { scenario_type: row.scenario_type });
  }

  if (queueFull) {
    queueFullCount.add(1, { scenario_type: row.scenario_type });
  }

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

function buildScenarios() {
  if (IS_PEAK_ARRIVAL_RATE) {
    return {
      peak_arrival: {
        executor: 'constant-arrival-rate',
        exec: 'peakArrival',
        rate: PEAK_RATE,
        timeUnit: ARRIVAL_TIME_UNIT,
        duration: PEAK_DURATION,
        preAllocatedVUs: PRE_ALLOCATED_VUS,
        maxVUs: MAX_VUS,
      },
      tail_arrival: {
        executor: 'constant-arrival-rate',
        exec: 'tailArrival',
        rate: TAIL_RATE,
        timeUnit: ARRIVAL_TIME_UNIT,
        duration: TAIL_DURATION,
        startTime: PEAK_DURATION,
        preAllocatedVUs: PRE_ALLOCATED_VUS,
        maxVUs: MAX_VUS,
      },
    };
  }

  return {
    baseline_enrollment: {
      executor: 'shared-iterations',
      vus: EFFECTIVE_VUS,
      iterations: EFFECTIVE_ITERATIONS,
      maxDuration: MAX_DURATION,
    },
  };
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

function createReasonCounters() {
  return {
    CapacityExcessException: new Counter('baseline_reason_capacity_excess_total'),
    DuplicateEnrollmentException: new Counter('baseline_reason_duplicate_enrollment_total'),
    TimeConflictException: new Counter('baseline_reason_time_conflict_total'),
    AlreadyCompletedCourseException: new Counter('baseline_reason_already_completed_total'),
    PrerequisiteNotMetException: new Counter('baseline_reason_prerequisite_not_met_total'),
    ObjectOptimisticLockingFailureException: new Counter('baseline_reason_object_optimistic_locking_failure_total'),
    OptimisticLockException: new Counter('baseline_reason_optimistic_lock_total'),
    QueueFullException: new Counter('baseline_reason_queue_full_total'),
    SingleWriterResponseTimeoutException: new Counter('baseline_reason_single_writer_response_timeout_total'),
    InMemorySingleWriterResponseTimeoutException: new Counter('baseline_reason_in_memory_single_writer_response_timeout_total'),
    GlobalInMemorySingleWriterResponseTimeoutException: new Counter('baseline_reason_global_in_memory_single_writer_response_timeout_total'),
    Unknown: new Counter('baseline_reason_unknown_total'),
  };
}

function loadPayloadRows() {
  const raw = open(PAYLOAD_PATH).trim().replace(/^\uFEFF/, '');
  const [headerLine, ...lines] = raw.split(/\r?\n/);
  const headers = headerLine.split(',').map((header) => header.trim());
  validateHeaders(headers);

  let rows = lines.map((line, index) => {
    const values = line.split(',');
    const row = {
      request_id: index + 1,
      ...Object.fromEntries(headers.map((header, valueIndex) => [header, normalizeCell(values[valueIndex])])),
    };
    row.scenario_type = normalizeScenario(row.scenario_type);
    row.expected_status = normalizeCell(row.expected_status);
    return row;
  });

  validateRows(rows);

  rows = rows.sort((left, right) => {
    const leftOffset = Number(left.scheduled_offset_ms || 0);
    const rightOffset = Number(right.scheduled_offset_ms || 0);
    if (leftOffset !== rightOffset) {
      return leftOffset - rightOffset;
    }
    return Number(left.request_id) - Number(right.request_id);
  });

  if (SCENARIO_FILTER && SCENARIO_FILTER.toUpperCase() !== 'ALL') {
    const allowed = new Set(
      SCENARIO_FILTER
        .split(',')
        .map((scenario) => normalizeScenario(scenario))
        .filter(Boolean)
    );
    rows = rows.filter((row) => allowed.has(row.scenario_type));
  }

  if (LIMIT > 0) {
    rows = rows.slice(0, LIMIT);
  }

  return rows;
}

function addReason(reason, scenarioType) {
  if (!reason) {
    return;
  }

  const key = reasonCounters[reason] ? reason : 'Unknown';
  reasonCounters[key].add(1, {
    scenario_type: scenarioType,
    reason,
  });
}

function addScenarioStatus(scenarioType, statusBucket) {
  if (!statusCounters[scenarioType]) {
    payloadStructureInvalid.add(1, { reason: 'unknown_scenario_type', scenario_type: String(scenarioType || '') });
    fail(`Unknown scenario_type loaded by k6: ${scenarioType}`);
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

function normalizeCell(value) {
  return value === undefined || value === null ? '' : String(value).trim();
}

function normalizeScenario(value) {
  return normalizeCell(value).toUpperCase();
}

function validateHeaders(headers) {
  const missing = REQUIRED_COLUMNS.filter((column) => !headers.includes(column));
  if (missing.length > 0) {
    throw new Error(`Invalid payload CSV. Missing columns: ${missing.join(', ')}`);
  }
}

function validateRows(rows) {
  const blankScenario = rows.find((row) => !row.scenario_type);
  if (blankScenario) {
    throw new Error(`Invalid payload CSV. scenario_type is blank at request_id=${blankScenario.request_id}`);
  }

  const blankExpectedStatus = rows.find((row) => !row.expected_status);
  if (blankExpectedStatus) {
    throw new Error(`Invalid payload CSV. expected_status is blank at request_id=${blankExpectedStatus.request_id}`);
  }

  const unknownScenario = rows.find((row) => !SCENARIOS.includes(row.scenario_type));
  if (unknownScenario) {
    throw new Error(`Invalid payload CSV. unknown scenario_type=${unknownScenario.scenario_type} at request_id=${unknownScenario.request_id}`);
  }
}

function countValues(rows, field, knownValues) {
  const counts = {};
  for (const value of knownValues) {
    counts[value] = 0;
  }

  for (const row of rows) {
    const value = row[field] || '';
    counts[value] = (counts[value] || 0) + 1;
  }

  return counts;
}

function validateLoadedPayloadDistribution(scenarioDistribution, statusDistribution, payloadCount) {
  if (payloadCount === 0) {
    throw new Error(`No payload rows loaded from ${PAYLOAD_PATH}. SCENARIO_FILTER=${SCENARIO_FILTER || 'ALL'}, LIMIT=${LIMIT || 'ALL'}`);
  }

  const filter = SCENARIO_FILTER.toUpperCase();
  if (!filter || filter === 'ALL') {
    const nonNormalCount = SCENARIOS
      .filter((scenario) => scenario !== 'NORMAL')
      .reduce((sum, scenario) => sum + (scenarioDistribution[scenario] || 0), 0);

    if (scenarioDistribution.NORMAL === payloadCount && nonNormalCount === 0) {
      throw new Error('Invalid payload distribution. All loaded rows are NORMAL; expected HOTSPOT/failure scenarios are missing.');
    }

    const successCount = (scenarioDistribution.NORMAL || 0) + (scenarioDistribution.HOTSPOT || 0);
    const fourXxCount = (statusDistribution['400'] || 0) + (statusDistribution['409'] || 0);
    if (successCount !== (statusDistribution['200'] || 0) || fourXxCount === 0) {
      throw new Error(`Invalid payload expected_status distribution. scenario success=${successCount}, status200=${statusDistribution['200'] || 0}, status4xx=${fourXxCount}`);
    }
  }
}

function validateExecutorMode() {
  const supportedModes = ['shared-iterations', 'peak-arrival-rate'];
  if (!supportedModes.includes(EXECUTOR_MODE)) {
    throw new Error(`Unsupported EXECUTOR_MODE=${EXECUTOR_MODE}. Supported modes: ${supportedModes.join(', ')}`);
  }

  if (!IS_PEAK_ARRIVAL_RATE) {
    return;
  }

  if (ARRIVAL_TIME_UNIT !== '1s') {
    throw new Error(`Unsupported ARRIVAL_TIME_UNIT=${ARRIVAL_TIME_UNIT}. Peak capacity planning currently expects 1s.`);
  }

  if (PEAK_RATE <= 0 || TAIL_RATE <= 0) {
    throw new Error(`Invalid peak arrival rates. PEAK_RATE=${PEAK_RATE}, TAIL_RATE=${TAIL_RATE}`);
  }

  if (PEAK_ITERATIONS + TAIL_ITERATIONS > payloads.length) {
    throw new Error(`Peak arrival test requires at least ${ARRIVAL_ITERATIONS} payload rows, but loaded ${payloads.length}. SCENARIO_FILTER=${SCENARIO_FILTER || 'ALL'}, LIMIT=${LIMIT || 'ALL'}`);
  }
}

function durationToSeconds(value) {
  const normalized = String(value || '').trim();
  const matched = normalized.match(/^(\d+(?:\.\d+)?)(ms|s|m)$/);
  if (!matched) {
    throw new Error(`Invalid duration=${value}. Supported units: ms, s, m`);
  }

  const amount = Number(matched[1]);
  const unit = matched[2];
  if (unit === 'ms') {
    return amount / 1000;
  }
  if (unit === 'm') {
    return amount * 60;
  }
  return amount;
}

function normalizeMetricPart(value) {
  return String(value).toLowerCase().replace(/[^a-z0-9]+/g, '_');
}

function toStatusBucket(actualStatus) {
  if (actualStatus === 0) {
    return '0';
  }

  if (actualStatus === 200) {
    return '200';
  }

  if (actualStatus === 202) {
    return '202';
  }

  if (actualStatus === 409) {
    return '409';
  }

  if (actualStatus === 429) {
    return '429';
  }

  if (actualStatus >= 500) {
    return '5xx';
  }

  if (actualStatus >= 400 && actualStatus < 500) {
    return '400';
  }

  return '5xx';
}

function isAccepted(actualStatus) {
  if (API_MODE === 'single-writer') {
    return actualStatus === 202;
  }

  return actualStatus === 200;
}

function isQueueFull(actualStatus) {
  return [
    'single-writer',
    'single-writer-sync',
    'in-memory-single-writer',
    'global-in-memory-single-writer',
  ].includes(API_MODE) && actualStatus === 429;
}

function isStrictMismatch(expectedStatus, actualStatus) {
  if (API_MODE === 'single-writer') {
    return actualStatus !== 202 && actualStatus !== 429;
  }

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

  if (API_MODE === 'single-writer') {
    return false;
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
  const statusZero = metrics.baseline_status_zero_total?.values || {};
  const realFiveXx = metrics.baseline_real_5xx_total?.values || {};
  const k6Errors = metrics.baseline_k6_error_total?.values || {};
  const accepted = metrics.baseline_accepted_total?.values || {};
  const queueFull = metrics.baseline_queue_full_total?.values || {};
  const droppedIterations = metrics.dropped_iterations?.values || {};

  return [
    '',
    'Baseline enrollment load test summary',
    `- api mode: ${API_MODE}`,
    `- base path: ${BASE_PATH}`,
    `- executor mode: ${EXECUTOR_MODE}`,
    `- scenario filter: ${SCENARIO_FILTER || 'ALL'}`,
    `- payload limit: ${LIMIT || 'ALL'}`,
    `- ignore schedule: ${IS_PEAK_ARRIVAL_RATE ? 'true (forced by peak-arrival-rate)' : IGNORE_SCHEDULE}`,
    `- effective iterations: ${IS_PEAK_ARRIVAL_RATE ? ARRIVAL_ITERATIONS : EFFECTIVE_ITERATIONS}`,
    `- peak arrival: ${formatArrivalPlan()}`,
    `- requested VUs: ${REQUESTED_VUS}`,
    `- effective VUs: ${IS_PEAK_ARRIVAL_RATE ? `${PRE_ALLOCATED_VUS} pre-allocated, ${MAX_VUS} max` : EFFECTIVE_VUS}`,
    `- loaded payload rows: ${payloads.length}`,
    `- requests: ${requests.count ?? 0}`,
    `- dropped iterations: ${droppedIterations.count ?? 0}`,
    `- request rate: ${formatNumber(requests.rate)} req/s`,
    `- accepted count: ${accepted.count ?? 0}`,
    `- queue full count: ${queueFull.count ?? 0}`,
    `- strict mismatch count: ${strictMismatch.count ?? 0}`,
    `- critical mismatch count: ${critical.count ?? 0}`,
    `- system failure rate: ${formatNumber((systemFailure.rate ?? 0) * 100)}%`,
    `- system failure count: ${systemFailureTotal.count ?? 0}`,
    `- status 0 count: ${statusZero.count ?? 0}`,
    `- real 5xx count: ${realFiveXx.count ?? 0}`,
    `- k6 error count: ${k6Errors.count ?? 0}`,
    `- latency p95: ${formatNumber(duration['p(95)'])} ms`,
    `- latency p99: ${formatNumber(duration['p(99)'])} ms`,
    '',
    'Payload scenario distribution',
    formatDistribution(PAYLOAD_SCENARIO_DISTRIBUTION, SCENARIOS),
    '',
    'Payload expected_status distribution',
    formatDistribution(PAYLOAD_EXPECTED_STATUS_DISTRIBUTION, ['200', '400', '409']),
    '',
    'Scenario status count',
    formatScenarioStatusCounts(metrics),
    '',
    'Response body reason count',
    formatReasonCounts(metrics),
    '',
    'k6 error reason count',
    formatK6ErrorReasonCounts(data),
    '',
    'Status 0 samples',
    formatStatusZeroSamples(data),
    '',
    'Mismatch samples',
    formatMismatchSamples(data),
    '',
  ].join('\n');
}

function formatArrivalPlan() {
  if (!IS_PEAK_ARRIVAL_RATE) {
    return '-';
  }

  return `${PEAK_RATE}/s for ${PEAK_DURATION} (${PEAK_ITERATIONS}), then ${TAIL_RATE}/s for ${TAIL_DURATION} (${TAIL_ITERATIONS})`;
}

function validateApiMode() {
  const supportedModes = [
    'baseline',
    'optimistic',
    'single-writer',
    'single-writer-sync',
    'in-memory-single-writer',
    'global-in-memory-single-writer',
  ];
  if (!supportedModes.includes(API_MODE) && !__ENV.BASE_PATH) {
    throw new Error(`Unsupported API_MODE=${API_MODE}. Supported modes: ${supportedModes.join(', ')}`);
  }
}

function pathForApiMode(mode) {
  if (mode === 'optimistic') {
    return '/api/optimistic/enrollments';
  }
  if (mode === 'single-writer') {
    return '/api/single-writer/enrollments';
  }
  if (mode === 'single-writer-sync') {
    return '/api/single-writer-sync/enrollments';
  }
  if (mode === 'in-memory-single-writer') {
    return '/api/in-memory-single-writer/enrollments';
  }
  if (mode === 'global-in-memory-single-writer') {
    return '/api/global-in-memory-single-writer/enrollments';
  }
  return '/api/baseline/enrollments';
}

function normalizeBasePath(value) {
  const normalized = normalizeCell(value);
  if (!normalized) {
    return '/api/baseline/enrollments';
  }
  return normalized.startsWith('/') ? normalized : `/${normalized}`;
}

function formatDistribution(distribution, keys) {
  return keys
    .map((key) => `- ${key}: ${distribution[key] || 0}`)
    .join('\n');
}

function formatReasonCounts(metrics) {
  return [
    `- CapacityExcessException: ${metricCount(metrics, 'baseline_reason_capacity_excess_total')}`,
    `- DuplicateEnrollmentException: ${metricCount(metrics, 'baseline_reason_duplicate_enrollment_total')}`,
    `- TimeConflictException: ${metricCount(metrics, 'baseline_reason_time_conflict_total')}`,
    `- AlreadyCompletedCourseException: ${metricCount(metrics, 'baseline_reason_already_completed_total')}`,
    `- PrerequisiteNotMetException: ${metricCount(metrics, 'baseline_reason_prerequisite_not_met_total')}`,
    `- ObjectOptimisticLockingFailureException: ${metricCount(metrics, 'baseline_reason_object_optimistic_locking_failure_total')}`,
    `- OptimisticLockException: ${metricCount(metrics, 'baseline_reason_optimistic_lock_total')}`,
    `- QueueFullException: ${metricCount(metrics, 'baseline_reason_queue_full_total')}`,
    `- SingleWriterResponseTimeoutException: ${metricCount(metrics, 'baseline_reason_single_writer_response_timeout_total')}`,
    `- InMemorySingleWriterResponseTimeoutException: ${metricCount(metrics, 'baseline_reason_in_memory_single_writer_response_timeout_total')}`,
    `- GlobalInMemorySingleWriterResponseTimeoutException: ${metricCount(metrics, 'baseline_reason_global_in_memory_single_writer_response_timeout_total')}`,
    `- Unknown: ${metricCount(metrics, 'baseline_reason_unknown_total')}`,
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
    ...extractK6Error(response),
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
    error_code: String(sample.error_code || ''),
  });

  check(response, {
    [sampleCheckName(sample)]: () => false,
  });

  // k6 does not reliably aggregate arbitrary mutable JS arrays from multiple VUs
  // into handleSummary. Logging the same compact sample guarantees visibility
  // during smoke tests and preserves the summary path when VU-local data is available.
  console.error(`MISMATCH_SAMPLE ${JSON.stringify(sample)}`);
}

function sampleCheckName(sample) {
  return `mismatch_sample|${encodeURIComponent(JSON.stringify({
    request_id: sample.request_id,
    scenario_type: sample.scenario_type,
    expected_status: sample.expected_status,
    actual_status: sample.actual_status,
    student_id: sample.student_id,
    course_id: sample.course_id,
    response_body: sample.response_body.slice(0, 300),
    error_code: sample.error_code,
    error: sample.error,
    error_message: sample.error_message,
  }))}`;
}

function sanitizeBody(body) {
  if (!body) {
    return '';
  }

  const normalized = String(body).replace(/\s+/g, ' ').trim();
  return normalized.length > 500 ? `${normalized.slice(0, 500)}...` : normalized;
}

function extractK6Error(response) {
  return {
    error_code: normalizeErrorField(response?.error_code),
    error: normalizeErrorField(response?.error),
    error_message: normalizeErrorField(response?.error_message || response?.error),
  };
}

function normalizeErrorField(value) {
  if (value === undefined || value === null || value === '') {
    return '';
  }
  return sanitizeBody(String(value));
}

function addK6ErrorReason(response, k6Error) {
  const signature = {
    error_code: k6Error.error_code || '-',
    error: k6Error.error || '-',
    error_message: k6Error.error_message || '-',
  };
  check(response, {
    [`k6_error_reason|${encodeURIComponent(JSON.stringify(signature))}`]: () => false,
  });
}

function addStatusZeroSample(row, response, k6Error) {
  const sample = {
    request_id: row.request_id,
    scenario_type: row.scenario_type,
    expected_status: row.expected_status,
    actual_status: 0,
    student_id: row.student_id,
    course_id: row.course_id,
    error_code: k6Error.error_code,
    error: k6Error.error,
    error_message: k6Error.error_message,
  };

  if (statusZeroSamples.length < SAMPLE_LIMIT) {
    statusZeroSamples.push(sample);
  }

  check(response, {
    [`status_zero_sample|${encodeURIComponent(JSON.stringify(sample))}`]: () => false,
  });
  console.error(`STATUS_ZERO_SAMPLE ${JSON.stringify(sample)}`);
}

function extractReason(response) {
  if (!response || !response.body || response.status < 400) {
    return '';
  }

  try {
    const parsed = JSON.parse(response.body);
    return parsed.reason || parsed.error || parsed.exception || parsed.code || 'Unknown';
  } catch (error) {
    const body = String(response.body);
    const known = [
      'CapacityExcessException',
      'DuplicateEnrollmentException',
      'TimeConflictException',
      'AlreadyCompletedCourseException',
      'PrerequisiteNotMetException',
      'ObjectOptimisticLockingFailureException',
      'OptimisticLockException',
      'QueueFullException',
      'SingleWriterResponseTimeoutException',
      'InMemorySingleWriterResponseTimeoutException',
      'GlobalInMemorySingleWriterResponseTimeoutException',
    ];
    for (const reason of known) {
      if (body.includes(reason)) {
        return reason;
      }
    }
    return 'Unknown';
  }
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

function formatK6ErrorReasonCounts(data) {
  const reasons = checksWithPrefix(data, 'k6_error_reason|')
    .map((item) => ({
      signature: decodeCheckPayload(item.name, 'k6_error_reason|'),
      count: item.fails ?? 0,
    }))
    .filter((item) => item.signature)
    .sort((left, right) => right.count - left.count);

  if (reasons.length === 0) {
    return '- no k6 errors';
  }

  return reasons
    .map((item) => [
      `- count: ${item.count}`,
      `  error_code: ${item.signature.error_code || '-'}`,
      `  error: ${item.signature.error || '-'}`,
      `  error_message: ${item.signature.error_message || '-'}`,
    ].join('\n'))
    .join('\n');
}

function formatStatusZeroSamples(data) {
  const aggregatedSamples = checksWithPrefix(data, 'status_zero_sample|')
    .slice(0, SAMPLE_LIMIT)
    .map((item) => decodeCheckPayload(item.name, 'status_zero_sample|'))
    .filter(Boolean);
  const samples = aggregatedSamples.length > 0
    ? aggregatedSamples
    : statusZeroSamples.slice(0, SAMPLE_LIMIT);

  if (samples.length === 0) {
    return '- no status 0 samples';
  }

  return samples
    .map((sample, index) => formatStatusZeroSample(sample, index))
    .join('\n');
}

function formatStatusZeroSample(sample, index) {
  return [
    `Sample ${index + 1}`,
    `  request_id: ${sample.request_id}`,
    `  scenario_type: ${sample.scenario_type}`,
    `  expected_status: ${sample.expected_status}`,
    '  actual_status: 0',
    `  student_id: ${sample.student_id}`,
    `  course_id: ${sample.course_id}`,
    `  error_code: ${sample.error_code || '-'}`,
    `  error: ${sample.error || '-'}`,
    `  error_message: ${sample.error_message || '-'}`,
  ].join('\n');
}

function checksWithPrefix(data, prefix) {
  return (data.root_group?.checks || [])
    .filter((item) => item.name && item.name.startsWith(prefix));
}

function decodeCheckPayload(name, prefix) {
  try {
    return JSON.parse(decodeURIComponent(name.slice(prefix.length)));
  } catch (error) {
    return null;
  }
}

function metricCount(metrics, metricName) {
  return metrics[metricName]?.values?.count ?? 0;
}

function formatMismatchSamples(data) {
  if (responseSamples.length > 0) {
    return responseSamples
      .slice(0, SAMPLE_LIMIT)
      .map((sample, index) => formatSample(sample, index))
      .join('\n');
  }

  const checkSamples = formatMismatchSamplesFromChecks(data);
  if (checkSamples) {
    return checkSamples;
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
    `  error_code: ${sample.error_code || '-'}`,
    `  error: ${sample.error || '-'}`,
    `  error_message: ${sample.error_message || '-'}`,
    `  response_body: ${sample.response_body || '-'}`,
  ].join('\n');
}

function formatMismatchSamplesFromChecks(data) {
  const checks = data.root_group?.checks || [];
  const samples = checks
    .filter((item) => item.name && item.name.startsWith('mismatch_sample|'))
    .slice(0, SAMPLE_LIMIT)
    .map((item) => decodeSampleCheckName(item.name))
    .filter(Boolean);

  if (samples.length === 0) {
    return '';
  }

  return samples
    .map((sample, index) => formatSample(sample, index))
    .join('\n');
}

function decodeSampleCheckName(name) {
  try {
    return JSON.parse(decodeURIComponent(name.slice('mismatch_sample|'.length)));
  } catch (error) {
    return null;
  }
}

function formatNumber(value) {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '0';
  }
  return Number(value).toFixed(2);
}
