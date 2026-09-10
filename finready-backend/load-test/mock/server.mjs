import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export function configuration(env = process.env) {
  const number = (name, fallback) => {
    const value = Number(env[name] ?? fallback);
    if (!Number.isInteger(value) || value < 0 || value > 300000) throw new Error(`Invalid ${name}`);
    return value;
  };
  const failureMode = env.FAILURE_MODE ?? 'none';
  if (!['none', 'http500', 'malformed', 'timeout'].includes(failureMode)) throw new Error('Invalid FAILURE_MODE');
  const failureBatch = env.FAILURE_BATCH ?? 'R04';
  if (!['R01', 'R04', 'R07'].includes(failureBatch)) throw new Error('Invalid FAILURE_BATCH');
  return {
    classifierDelayMs: number('CLASSIFIER_DELAY_MS', 8000),
    verifierDelayMs: number('VERIFIER_DELAY_MS', 5000),
    timeoutDelayMs: number('TIMEOUT_DELAY_MS', 65000),
    failureMode, failureBatch,
  };
}

function textContent(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.filter(x => x.type === 'text').map(x => x.text).join('');
  throw new Error('Expected text content');
}

export function parseRequest(body) {
  const user = textContent(body.messages?.find(m => m.role === 'user')?.content);
  if (user.startsWith('## 판정 대상\n\n')) {
    const match = /^## 판정 대상\n\n([^\n]+)\n\n## 상담 내용\n\n([\s\S]+)$/.exec(user);
    if (!match) throw new Error('Unexpected classifier prompt');
    const ids = match[1].split(',').map(x => x.trim());
    if (ids.length > 3 || new Set(ids).size !== ids.length || ids.some(id => !/^R0[1-9]$/.test(id))) {
      throw new Error('Expected a batch of 1..3 distinct Risk IDs');
    }
    // Synthetic transport fixture, NOT a financial judgement. Real provenance validation still runs.
    const evidence = match[2].trim().slice(0, 120);
    if (evidence.length < 15) throw new Error('Transcript too short for provenance fixture');
    return { stage: 'classifier', batch: ids[0], results: ids.map(riskId => ({
      riskId, status: 'EXPLAINED', reason: '로컬 부하 테스트 고정 응답', evidenceText: evidence,
    })) };
  }
  if (user.startsWith('## 검증 대상\n\n')) {
    const targets = user.split('## 상담 내용 전체')[0];
    const ids = [...targets.matchAll(/^### (R0[1-9]) — /gm)].map(m => m[1]);
    if (!ids.length || new Set(ids).size !== ids.length) throw new Error('Invalid verifier targets');
    return { stage: 'verifier', batch: 'all', results: ids.map(riskId => ({
      riskId, relation: 'SUPPORTS', reason: '로컬 부하 테스트 고정 응답',
    })) };
  }
  throw new Error('Unsupported prompt; only Coverage classifier and verifier are mocked');
}

export function createMockServer(config = configuration()) {
  const counters = {};
  for (const batch of ['R01', 'R04', 'R07']) counters[`classifier:${batch}`] = { total: 0, active: 0, peak: 0, completed: 0 };
  counters['verifier:all'] = { total: 0, active: 0, peak: 0, completed: 0 };
  let active = 0;
  let peak = 0;
  const json = (res, status, body) => {
    if (res.destroyed) return;
    res.writeHead(status, { 'Content-Type': 'application/json', 'request-id': randomUUID() });
    res.end(JSON.stringify(body));
  };
  const server = http.createServer(async (req, res) => {
    if (req.method === 'GET' && req.url === '/health') return json(res, 200, { status: 'UP', mock: true });
    if (req.method === 'GET' && req.url === '/stats') return json(res, 200, { config, active, peak, counters });
    if (req.method === 'GET' && req.url === '/metrics') {
      const lines = [
        '# TYPE mock_claude_active gauge', `mock_claude_active ${active}`,
        '# TYPE mock_claude_peak gauge', `mock_claude_peak ${peak}`,
        '# TYPE mock_claude_requests_total counter',
      ];
      for (const [key, value] of Object.entries(counters)) {
        const [stage, batch] = key.split(':');
        const labels = `{stage="${stage}",batch="${batch}"}`;
        lines.push(`mock_claude_requests_total${labels} ${value.total}`);
        lines.push(`mock_claude_batch_active${labels} ${value.active}`);
        lines.push(`mock_claude_completed_total${labels} ${value.completed}`);
      }
      res.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4' });
      return res.end(lines.join('\n') + '\n');
    }
    if (req.method !== 'POST' || req.url !== '/v1/messages') return json(res, 404, { error: 'Not found' });
    let entry;
    let released = false;
    const release = () => {
      if (entry && !released) { entry.active--; active--; released = true; }
    };
    try {
      let data = '';
      req.setEncoding('utf8');
      for await (const chunk of req) {
        data += chunk;
        if (data.length > 1000000) return json(res, 413, { error: 'Body too large' });
      }
      const body = JSON.parse(data);
      const request = parseRequest(body);
      entry = counters[`${request.stage}:${request.batch}`];
      if (!entry) throw new Error('Unexpected batch start');
      entry.total++; entry.active++; active++;
      entry.peak = Math.max(entry.peak, entry.active); peak = Math.max(peak, active);
      const fails = request.stage === 'classifier' && request.batch === config.failureBatch;
      const mode = fails ? config.failureMode : 'none';
      const delay = mode === 'timeout' ? config.timeoutDelayMs
        : request.stage === 'classifier' ? config.classifierDelayMs : config.verifierDelayMs;
      // Async timer: the fake provider itself must accept overlapping HTTP requests.
      await new Promise(resolve => {
        const onClose = () => { clearTimeout(timer); release(); resolve(); };
        const timer = setTimeout(() => { res.off('close', onClose); resolve(); }, delay);
        res.once('close', onClose);
      });
      if (res.destroyed) return;
      if (mode === 'http500') {
        json(res, 500, { type: 'error', error: { type: 'api_error', message: 'Injected local batch failure' } });
      } else {
        json(res, 200, {
          id: `msg_${randomUUID()}`, type: 'message', role: 'assistant', model: body.model,
          content: [{ type: 'text', text: mode === 'malformed' ? 'not-json' : JSON.stringify({ results: request.results }) }],
          stop_reason: 'end_turn', stop_sequence: null,
          usage: { input_tokens: 100, output_tokens: 100, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
        });
      }
      entry.completed++;
    } catch (error) {
      json(res, 400, { type: 'error', error: { type: 'invalid_request_error', message: error.message } });
    } finally { release(); }
  });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createMockServer().listen(8081, '0.0.0.0', () => console.log('Local mock Claude listening on 8081'));
}
