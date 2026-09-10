import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { configuration, createMockServer, parseRequest } from './server.mjs';

const transcript = '이 문장은 로컬 부하 테스트를 위한 고정 상담 원문이며 금융 판정을 검증하지 않습니다.';
function body(ids) {
  return { model: 'mock', messages: [{ role: 'user', content: `## 판정 대상\n\n${ids.join(', ')}\n\n## 상담 내용\n\n${transcript}\n` }] };
}
async function withServer(config, action) {
  const server = createMockServer(configuration(config));
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try { await action(`http://127.0.0.1:${server.address().port}`); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}
const post = (url, ids) => fetch(url + '/v1/messages', { method: 'POST', body: JSON.stringify(body(ids)) });

test('three requests overlap, return scoped IDs and exact transcript evidence', async () => {
  await withServer({ CLASSIFIER_DELAY_MS: '80' }, async url => {
    const groups = [['R01', 'R02', 'R03'], ['R04', 'R05', 'R06'], ['R07', 'R08', 'R09']];
    const responses = await Promise.all(groups.map(ids => post(url, ids)));
    for (let i = 0; i < responses.length; i++) {
      assert.equal(responses[i].status, 200);
      const message = await responses[i].json();
      const results = JSON.parse(message.content[0].text).results;
      assert.deepEqual(results.map(x => x.riskId), groups[i]);
      assert.ok(results.every(x => transcript.includes(x.evidenceText)));
    }
    const stats = await (await fetch(url + '/stats')).json();
    assert.equal(stats.peak, 3);
    assert.equal(stats.active, 0);
    assert.match(await (await fetch(url + '/metrics')).text(), /mock_claude_requests_total.* 1/);
  });
});

test('malformed output applies only to selected batch, including repeated calls', async () => {
  await withServer({ CLASSIFIER_DELAY_MS: '0', FAILURE_MODE: 'malformed' }, async url => {
    for (let i = 0; i < 2; i++) {
      const broken = await (await post(url, ['R04', 'R05', 'R06'])).json();
      assert.equal(broken.content[0].text, 'not-json');
    }
    const good = await (await post(url, ['R01', 'R02', 'R03'])).json();
    assert.equal(JSON.parse(good.content[0].text).results.length, 3);
  });
});

test('HTTP failure has Anthropic error envelope', async () => {
  await withServer({ CLASSIFIER_DELAY_MS: '0', FAILURE_MODE: 'http500' }, async url => {
    const response = await post(url, ['R04', 'R05', 'R06']);
    assert.equal(response.status, 500);
    assert.equal((await response.json()).error.type, 'api_error');
  });
});

test('verifier excludes IDs occurring in transcript', () => {
  const request = parseRequest({ messages: [{ role: 'user', content:
    '## 검증 대상\n\n### R01 — 원금\n사실: 원금\n인용된 근거: 문장\n\n## 상담 내용 전체 (맥락 확인용)\n\n### R09 — 다른 항목' }] });
  assert.deepEqual(request.results.map(x => x.riskId), ['R01']);
});

test('invalid batches and failure configuration are rejected', () => {
  assert.throws(() => parseRequest(body(['R01', 'R01'])));
  assert.throws(() => configuration({ FAILURE_MODE: 'typo' }));
});

test('timeout client disconnect releases the active request', async () => {
  await withServer({ CLASSIFIER_DELAY_MS: '0', FAILURE_MODE: 'timeout', TIMEOUT_DELAY_MS: '5000' }, async url => {
    const controller = new AbortController();
    const pending = fetch(url + '/v1/messages', {
      method: 'POST', body: JSON.stringify(body(['R04', 'R05', 'R06'])), signal: controller.signal,
    }).catch(error => error);
    let stats;
    for (let i = 0; i < 100; i++) {
      stats = await (await fetch(url + '/stats')).json();
      if (stats.active === 1) break;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(stats.active, 1);
    controller.abort();
    assert.equal((await pending).name, 'AbortError');
    for (let i = 0; i < 100; i++) {
      stats = await (await fetch(url + '/stats')).json();
      if (stats.active === 0) break;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(stats.active, 0);
    assert.equal(stats.counters['classifier:R04'].completed, 0);
  });
});
