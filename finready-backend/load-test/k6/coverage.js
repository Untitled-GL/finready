import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://backend:8080';
const mock = __ENV.MOCK_URL || 'http://mock-claude:8081';
const mode = __ENV.MODE || 'smoke';
if (!['smoke', 'load', 'failure'].includes(mode)) throw new Error('MODE must be smoke, load or failure');
// This fixture's synthetic all-EXPLAINED responses must never reach deployed environments.
if (base !== 'http://backend:8080' || mock !== 'http://mock-claude:8081') {
  throw new Error('Run this script inside the isolated load-test Compose network');
}
const coverageLatency = new Trend('coverage_duration', true);
const classifierLatency = new Trend('classifier_duration', true);
const verifierLatency = new Trend('verifier_duration', true);
const coverage503 = new Rate('coverage_503');
const testid = __ENV.TEST_ID || `${mode}-${Date.now()}`;
export const options = {
  tags: { testid, mode },
  // Omit URL tags: unique session paths would create one time series per session.
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  setupTimeout: '180s',
  scenarios: {
    coverage: mode === 'load' ? {
      executor: 'ramping-vus', startVUs: 0,
      stages: [{ duration: '30s', target: 1 }, { duration: '60s', target: 3 },
        { duration: '60s', target: 5 }, { duration: '60s', target: 10 }, { duration: '30s', target: 0 }],
      gracefulRampDown: '180s', gracefulStop: '180s',
    } : { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate<0.01'],
    ...(mode === 'failure' ? { coverage_503: ['rate==1'] }
      : { coverage_duration: [mode === 'smoke' ? 'max<30000' : 'p(95)<30000'], coverage_503: ['rate==0'] }),
  },
};

function params(name, expected = 200) {
  return { headers: { 'Content-Type': 'application/json' }, tags: { name },
    timeout: '300s', responseCallback: http.expectedStatuses(expected) };
}
function requiredJson(response, expected, name) {
  if (!check(response, { [`${name}: HTTP ${expected}`]: r => r.status === expected })) {
    fail(`${name} returned ${response.status}: ${response.body?.slice(0, 300)}`);
  }
  return response.json();
}
export function setup() {
  // Readiness requests do not dilute API failure rates or latency measurements.
  for (let i = 0; i < 60; i++) {
    const ready = http.get(base + '/actuator/health', {
      tags: { name: 'readiness' }, timeout: '2s', responseCallback: null,
    });
    if (ready.status === 200) break;
    if (i === 59) fail('Backend not ready; inspect docker compose logs backend');
    sleep(1);
  }
  const stats = requiredJson(http.get(mock + '/stats', params('mock_stats')), 200, 'mock_stats');
  if ((mode === 'failure') !== (stats.config.failureMode !== 'none')) {
    fail('MODE and FAILURE_MODE disagree; recreate mock-claude with the documented settings');
  }
  const demo = requiredJson(http.get(base + '/api/products/demo', params('demo')), 200, 'demo');
  const transcript = demo.demoPresets?.find(x => x.id === 'main')?.transcript;
  if (!transcript || demo.risks.length !== 9) fail('Expected seeded PROD_A with main transcript and 9 risks');
  return { productId: demo.product.id, customerId: demo.customers[0].id, transcript, stats };
}

export default function (data) {
  const session = requiredJson(http.post(base + '/api/sessions', JSON.stringify({
    productId: data.productId, customerId: data.customerId,
  }), params('create_session', 201)), 201, 'create_session');
  const path = `${base}/api/sessions/${session.sessionId}`;
  const revision = requiredJson(http.post(path + '/revisions', JSON.stringify({ text: data.transcript }),
    params('create_revision', 201)), 201, 'create_revision');
  const response = http.post(path + '/coverage', JSON.stringify({ revisionId: revision.revisionId }),
    params('coverage', mode === 'failure' ? 503 : 200));
  coverageLatency.add(response.timings.duration);
  coverage503.add(response.status === 503);
  const result = requiredJson(response, mode === 'failure' ? 503 : 200, 'coverage');
  if (mode === 'failure') {
    const expected = data.stats.config.failureMode === 'malformed' ? 'AI_PARSING_FAILED' : 'AI_TIMEOUT';
    check(result, { 'original AI error preserved': r => r.code === expected && r.recoverable === true });
    console.log(`Failure session=${session.sessionId}, revision=${revision.revisionId}; run verify-db next.`);
  } else {
    check(result, {
      'all 9 risk IDs exactly once': r => r.risks?.length === 9 && new Set(r.risks.map(x => x.riskId)).size === 9,
      'provenance and verifier executed': r => r.risks.every(x => x.evidence?.provenanceValid && x.semanticRelation === 'SUPPORTS'),
      'real fan-out adapter used': r => r.analysis?.promptVersion?.includes('coverage-v3-b3'),
      'fresh analysis has both stage timings': r => r.analysis?.classifierLatencyMs > 0 && r.analysis?.verifierLatencyMs > 0,
    });
    classifierLatency.add(result.analysis.classifierLatencyMs);
    verifierLatency.add(result.analysis.verifierLatencyMs);
  }
  if (mode !== 'load') {
    const after = requiredJson(http.get(mock + '/stats', params('mock_stats')), 200, 'mock_stats');
    for (const batch of ['R01', 'R04', 'R07']) {
      const key = `classifier:${batch}`;
      const count = after.counters[key].total - data.stats.counters[key].total;
      const completed = after.counters[key].completed - data.stats.counters[key].completed;
      if (mode === 'failure' && batch === data.stats.config.failureBatch) {
        // SDK retries transport failures; AiGateway retries the logical attempt too.
        const expected = data.stats.config.failureMode === 'malformed' ? 2 : 4;
        check(count, { [`${batch}: only failed batch retried (${expected} HTTP calls)`]: x => x === expected });
      } else {
        check(count, { [`${batch}: one HTTP call`]: x => x === 1 });
        check(completed, { [`${batch}: sibling finished`]: x => x === 1 });
      }
    }
    const verifierCount = after.counters['verifier:all'].total - data.stats.counters['verifier:all'].total;
    check(verifierCount, { 'verifier only after full classifier success': x => x === (mode === 'failure' ? 0 : 1) });
    if (mode === 'smoke') check(after.peak, { 'three HTTP calls overlapped': n => n >= 3 });
  }
  sleep(1);
}
