import assert from 'node:assert/strict';
import test from 'node:test';
import WebSocket from 'ws';
import { startServer, type RunningServer } from './harness.ts';

const ADMIN = 'test-admin-token';

interface AnyMessage { [key: string]: unknown; type?: string; }

function collect(ws: WebSocket): AnyMessage[] {
  const inbox: AnyMessage[] = [];
  ws.on('message', (raw) => {
    try { inbox.push(JSON.parse(String(raw))); } catch { /* ignore */ }
  });
  return inbox;
}

async function waitFor(
  inbox: AnyMessage[],
  predicate: (m: AnyMessage) => boolean,
  label: string,
  timeout = 4000,
): Promise<AnyMessage> {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const found = inbox.find(predicate);
    if (found) return found;
    await new Promise((r) => setTimeout(r, 25));
  }
  throw new Error(`timed out waiting for ${label}; got ${inbox.map((m) => m.type).join(',')}`);
}

function openWs(base: string, role: string, extra = ''): WebSocket {
  const url = base.replace(/^http/, 'ws') + `/v1/present-connect?role=${role}${extra}`;
  return new WebSocket(url);
}

async function json(base: string, path: string, init?: RequestInit) {
  const response = await fetch(base + path, init);
  const text = await response.text();
  let body: unknown = null;
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: response.status, body, headers: response.headers, text };
}

test('serves the deck and blocks path traversal', async (t) => {
  const server: RunningServer = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const root = await json(server.base, '/');
  assert.equal(root.status, 200);
  assert.match(root.text, /НЕЙРО-СТЕНОГРАФИСТ/);

  for (const asset of ['/css/style.css', '/js/deck.js', '/vendor/anime.js', '/presenter', '/join']) {
    const response = await fetch(server.base + asset);
    assert.equal(response.status, 200, `${asset} should be served`);
    await response.arrayBuffer();
  }

  // Traversal must never reach the backend sources or the filesystem above.
  for (const attack of [
    '/../backend/package.json',
    '/..%2fbackend/package.json',
    '/%2e%2e/backend/package.json',
    '/css/../../backend/src/server.ts',
    '/../../etc/passwd',
  ]) {
    const response = await fetch(server.base + attack);
    const text = await response.text();
    assert.ok(
      response.status === 403 || text.startsWith('<!doctype'),
      `${attack} leaked content (status ${response.status}): ${text.slice(0, 80)}`,
    );
    assert.ok(!text.includes('"scripts"'), `${attack} leaked package.json`);
    assert.ok(!text.includes('import http'), `${attack} leaked server source`);
  }

  const health = await json(server.base, '/v1/health');
  assert.equal(health.status, 200);
  assert.equal((health.body as { ok: boolean }).ok, true);
});

test('presenter commands are authenticated and drive checkpoints', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  // No credentials -> rejected.
  const denied = await json(server.base, '/api/command', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ type: 'checkpoint.step', delta: 1 }),
  });
  assert.equal(denied.status, 401);

  // Wrong token -> rejected.
  const bad = await fetch(server.base + '/api/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ token: 'wrong' }),
  });
  assert.equal(bad.status, 401);

  // Correct token -> cookie, then commands work.
  const login = await fetch(server.base + '/api/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ token: ADMIN }),
  });
  assert.equal(login.status, 200);
  const cookie = login.headers.get('set-cookie')?.split(';')[0] ?? '';
  assert.match(cookie, /^presenter=/);
  assert.match(login.headers.get('set-cookie') ?? '', /HttpOnly/);
  assert.match(login.headers.get('set-cookie') ?? '', /SameSite=Strict/);

  const step = await json(server.base, '/api/command', {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'checkpoint.step', delta: 2 }),
  });
  assert.equal(step.status, 200);
  assert.equal((step.body as { slide_index: number }).slide_index, 2);

  // Screens polling the checkpoint see the same slide.
  const poll = await json(server.base, '/v1/presentation');
  assert.equal((poll.body as { slide_index: number }).slide_index, 2);
  assert.ok(Number((poll.body as { revision: number }).revision) > 1);

  // Named slide + clamping at both ends.
  const goto = await json(server.base, '/api/command', {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'checkpoint.goto', slide: 'numbers' }),
  });
  assert.equal((goto.body as { slide_id: string }).slide_id, 'numbers');

  // Stepping back past the first slide clamps instead of going negative.
  const back = await json(server.base, '/api/command', {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'checkpoint.step', delta: -20 }),
  });
  assert.equal(back.status, 200);
  assert.equal((back.body as { slide_index: number }).slide_index, 0);

  // A delta outside the schema range is rejected, not silently clamped.
  const tooFar = await json(server.base, '/api/command', {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'checkpoint.step', delta: -99 }),
  });
  assert.equal(tooFar.status, 400);

  const unknown = await json(server.base, '/api/command', {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'checkpoint.goto', slide: 'not-a-slide' }),
  });
  assert.equal(unknown.status, 400);

  // Login throttling.
  let throttled = 0;
  for (let i = 0; i < 12; i += 1) {
    const r = await fetch(server.base + '/api/login', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ token: 'wrong' }),
    });
    if (r.status === 429) throttled += 1;
  }
  assert.ok(throttled > 0, 'brute-force attempts should eventually be throttled');
});

test('phone transcript reaches every screen over WebSocket', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN, PING_INTERVAL_MS: '250' });
  t.after(() => server.stop());

  const screenA = openWs(server.base, 'screen');
  const screenB = openWs(server.base, 'screen');
  const phone = openWs(server.base, 'phone', '&device=phone_1');
  t.after(() => { screenA.close(); screenB.close(); phone.close(); });

  const inboxA = collect(screenA);
  const inboxB = collect(screenB);
  const inboxPhone = collect(phone);

  await new Promise((r) => screenA.once('open', r));
  await new Promise((r) => screenB.once('open', r));
  await new Promise((r) => phone.once('open', r));

  await waitFor(inboxA, (m) => m.type === 'snapshot', 'screen A snapshot');
  await waitFor(inboxB, (m) => m.type === 'snapshot', 'screen B snapshot');

  phone.send(JSON.stringify({
    type: 'hello',
    protocol: 'present-connect/1',
    max_streams: 20,
    sample_rate: 16_000,
    encoding: 'pcm_s16le',
    models: ['zipformer-ru-int8'],
    accepts_diarization: true,
  }));

  const helloOk = await waitFor(inboxPhone, (m) => m.type === 'hello.ok', 'hello.ok');
  assert.ok(typeof helloOk.session_id === 'string');
  assert.equal(helloOk.protocol, 'present-connect/1');

  const phoneSeen = await waitFor(inboxA, (m) => m.type === 'phone', 'phone state');
  assert.equal(phoneSeen.connected, true);
  assert.equal(phoneSeen.stub, false);

  // Server pings, phone answers pong (contract §2).
  const ping = await waitFor(inboxPhone, (m) => m.type === 'ping', 'ping', 5_000);
  phone.send(JSON.stringify({ type: 'pong', t: ping.t }));

  phone.send(JSON.stringify({
    type: 'transcript.final',
    stream_id: 's03',
    utterance: { speaker_id: 'speaker_3', start_ms: 1250, end_ms: 4380, text: 'Добрый день!' },
  }));

  const rowA = await waitFor(inboxA, (m) => m.type === 'transcript.final', 'screen A row');
  const rowB = await waitFor(inboxB, (m) => m.type === 'transcript.final', 'screen B row');
  for (const row of [rowA, rowB]) {
    const payload = row.row as { speaker_id: string; text: string; seat: number; source: string };
    assert.equal(payload.speaker_id, 'speaker_3');
    assert.equal(payload.text, 'Добрый день!');
    assert.equal(payload.seat, 3, 'seat is derived from speaker_id');
    assert.equal(payload.source, 'worker');
  }

  // Brief export keeps exactly the four documented fields.
  const exported = await json(server.base, '/v1/sessions/whatever/transcript');
  const rows = (exported.body as { transcript: Array<Record<string, unknown>> }).transcript;
  assert.equal(rows.length, 1);
  assert.deepEqual(Object.keys(rows[0]).sort(), ['end_ms', 'speaker_id', 'start_ms', 'text']);
});

test('losing the phone switches screens to flagged reserve mode', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const screen = openWs(server.base, 'screen');
  const phone = openWs(server.base, 'phone', '&device=phone_1');
  const inbox = collect(screen);
  const inboxPhone = collect(phone);
  t.after(() => { screen.close(); phone.close(); });

  await new Promise((r) => screen.once('open', r));
  await new Promise((r) => phone.once('open', r));
  await waitFor(inbox, (m) => m.type === 'snapshot', 'snapshot');

  phone.send(JSON.stringify({ type: 'hello', protocol: 'present-connect/1', models: ['zipformer-ru-int8'] }));
  await waitFor(inboxPhone, (m) => m.type === 'hello.ok', 'hello.ok');

  // Presenter starts the live demo.
  const login = await fetch(server.base + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: ADMIN }),
  });
  const cookie = login.headers.get('set-cookie')?.split(';')[0] ?? '';
  await json(server.base, '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'demo.begin' }),
  });
  const live = await waitFor(inbox, (m) => m.type === 'demo' && m.phase === 'live', 'live phase');
  assert.equal(live.banner, null);

  // Phone vanishes -> fallback with an explicit banner and reserve rows.
  phone.terminate();
  const fallback = await waitFor(inbox, (m) => m.type === 'demo' && m.phase === 'fallback', 'fallback phase');
  assert.equal(fallback.reason, 'phone_disconnected');
  assert.ok(typeof fallback.banner === 'string' && fallback.banner.length > 0, 'must show a banner');

  const replay = await waitFor(inbox, (m) => m.type === 'transcript.replay', 'reserve rows');
  const rows = replay.rows as Array<{ source: string; speaker_id: string }>;
  assert.equal(rows.length, 6);
  for (const row of rows) assert.equal(row.source, 'replay', 'reserve rows must be tagged replay');

  // Reserve rows never leak into the brief export.
  const exported = await json(server.base, '/v1/sessions/x/transcript');
  assert.deepEqual((exported.body as { transcript: unknown[] }).transcript, []);
});

test('HTTP ingest works without a WebSocket phone link', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const screen = openWs(server.base, 'screen');
  const inbox = collect(screen);
  t.after(() => screen.close());
  await new Promise((r) => screen.once('open', r));
  await waitFor(inbox, (m) => m.type === 'snapshot', 'snapshot');

  const push = await json(server.base, '/v1/transcript', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      engine: 'zipformer-ru-int8',
      transcript: [
        { speaker_id: 'speaker_1', start_ms: 800, end_ms: 6400, text: 'Первое место говорит.' },
        { speaker_id: 'speaker_6', start_ms: 6500, end_ms: 9000, text: 'Шестое место отвечает.' },
      ],
    }),
  });
  assert.equal(push.status, 200);
  assert.equal((push.body as { accepted: number }).accepted, 2);

  await waitFor(inbox, (m) => m.type === 'transcript.final', 'broadcast row');

  const checkpoint = await json(server.base, '/v1/presentation');
  const phone = (checkpoint.body as { phone: { connected: boolean; engine: string | null } }).phone;
  assert.equal(phone.connected, true);
  assert.equal(phone.engine, 'zipformer-ru-int8');
  assert.equal((checkpoint.body as { demo_phase: string }).demo_phase, 'live');

  // A malformed body is rejected, not half-applied.
  const bad = await json(server.base, '/v1/transcript', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ transcript: [{ speaker_id: 'speaker_1', start_ms: -5, end_ms: 1, text: 'x' }] }),
  });
  assert.equal(bad.status, 400);

  const badShape = await json(server.base, '/v1/transcript', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ transcript: [{ speaker_id: 'speaker_1', start_ms: 5 }] }),
  });
  assert.equal(badShape.status, 400);

  // reset:true replaces the feed instead of stacking duplicates.
  await json(server.base, '/v1/transcript', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ reset: true, transcript: [{ speaker_id: 'speaker_2', start_ms: 10, end_ms: 20, text: 'Заново.' }] }),
  });
  const exported = await json(server.base, '/v1/sessions/x/transcript');
  const rows = (exported.body as { transcript: Array<{ speaker_id: string }> }).transcript;
  assert.equal(rows.length, 1);
  assert.equal(rows[0].speaker_id, 'speaker_2');
});

test('a stub worker (no models) never presents as live', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const screen = openWs(server.base, 'screen');
  const phone = openWs(server.base, 'phone');
  const inbox = collect(screen);
  const inboxPhone = collect(phone);
  t.after(() => { screen.close(); phone.close(); });

  await new Promise((r) => screen.once('open', r));
  await new Promise((r) => phone.once('open', r));
  await waitFor(inbox, (m) => m.type === 'snapshot', 'snapshot');

  phone.send(JSON.stringify({ type: 'hello', protocol: 'present-connect/1', models: [] }));
  await waitFor(inboxPhone, (m) => m.type === 'hello.ok', 'hello.ok');

  const phoneState = await waitFor(inbox, (m) => m.type === 'phone', 'phone state');
  assert.equal(phoneState.stub, true, 'no models means stub');

  const login = await fetch(server.base + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: ADMIN }),
  });
  const cookie = login.headers.get('set-cookie')?.split(';')[0] ?? '';
  await json(server.base, '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'demo.begin' }),
  });

  const fallback = await waitFor(inbox, (m) => m.type === 'demo', 'demo phase');
  assert.equal(fallback.phase, 'fallback', 'a stub phone must not claim live');
  assert.equal(fallback.reason, 'phone_stub_models_missing');
});
