import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
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

test('serves the built-in deck when the React build is absent, and blocks path traversal', async (t) => {
  const server: RunningServer = await startServer({ ADMIN_TOKEN: ADMIN, PRESENT_DIR: '/nonexistent-deck-build' });
  t.after(() => server.stop());

  const root = await json(server.base, '/');
  assert.equal(root.status, 200);
  assert.match(root.text, /НЕЙРО-СТЕНОГРАФИСТ/);

  for (const asset of ['/css/style.css', '/js/deck.js', '/vendor/anime.js', '/presenter', '/join', '/stage']) {
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

test('the built React deck takes / and the built-in deck stays at /deck-legacy', async (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'present-build-'));
  mkdirSync(join(dir, 'assets'));
  writeFileSync(join(dir, '_shell.html'), '<!doctype html><html><body>REACT_DECK_SHELL</body></html>');
  writeFileSync(join(dir, 'assets', 'index.js'), 'console.log("deck")');
  t.after(() => rmSync(dir, { recursive: true, force: true }));

  const server: RunningServer = await startServer({ ADMIN_TOKEN: ADMIN, PRESENT_DIR: dir });
  t.after(() => server.stop());

  const root = await json(server.base, '/');
  assert.equal(root.status, 200);
  assert.match(root.text, /REACT_DECK_SHELL/);

  // The deck's own hashed assets come from the build dir.
  const asset = await fetch(server.base + '/assets/index.js');
  assert.equal(asset.status, 200);
  assert.match(await asset.text(), /console\.log\("deck"\)/);

  // Join and stage are audience routes: the React deck, not the legacy one.
  for (const route of ['/join', '/stage']) {
    const response = await fetch(server.base + route);
    assert.match(await response.text(), /REACT_DECK_SHELL/, `${route} should show the React deck`);
  }

  // The built-in deck and the console stay reachable from publicDir.
  const legacy = await json(server.base, '/deck-legacy');
  assert.equal(legacy.status, 200);
  assert.match(legacy.text, /НЕЙРО-СТЕНОГРАФИСТ/);
  for (const asset of ['/js/deck.js', '/css/style.css', '/presenter']) {
    const response = await fetch(server.base + asset);
    assert.equal(response.status, 200, `${asset} should still be served`);
    await response.arrayBuffer();
  }

  // Traversal is blocked against both roots.
  for (const attack of ['/../backend/package.json', '/assets/../../backend/src/server.ts', '/../../etc/passwd']) {
    const response = await fetch(server.base + attack);
    const text = await response.text();
    assert.ok(
      response.status === 403 || text.startsWith('<!doctype') || text.includes('REACT_DECK_SHELL'),
      `${attack} leaked content (status ${response.status}): ${text.slice(0, 80)}`,
    );
    assert.ok(!text.includes('"scripts"'), `${attack} leaked package.json`);
    assert.ok(!text.includes('import http'), `${attack} leaked server source`);
  }
});

test('the phone WebSocket role is gated by PHONE_TOKEN too, not only HTTP ingest', async (t) => {
  const server: RunningServer = await startServer({ ADMIN_TOKEN: ADMIN, PHONE_TOKEN: 'phone-secret' });
  t.after(() => server.stop());

  const wsUrl = () => server.base.replace(/^http/, 'ws') + '/v1/present-connect?role=phone&device=probe';

  // Without the bearer header the phone role is refused at upgrade time. This
  // is the path `npm run sim:phone` hits against a host that sets a token, so
  // the rejection is asserted as a close code, not silently swallowed.
  const denied = new WebSocket(wsUrl());
  const deniedCode = await new Promise<number>((resolve, reject) => {
    denied.once('close', (code) => resolve(code));
    denied.once('error', reject);
  });
  assert.equal(deniedCode, 4001);

  // With it, hello/hello.ok completes and the worker is treated as a phone.
  const phone = new WebSocket(wsUrl(), { headers: { authorization: 'Bearer phone-secret' } });
  const inboxPhone = collect(phone);
  t.after(() => phone.close());
  await new Promise((r) => phone.once('open', r));
  phone.send(JSON.stringify({ type: 'hello', protocol: 'present-connect/1', models: ['zipformer-ru-int8'] }));
  const helloOk = await waitFor(inboxPhone, (m) => m.type === 'hello.ok', 'hello.ok with token');
  assert.equal(helloOk.protocol, 'present-connect/1');
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
    body: JSON.stringify({ type: 'checkpoint.goto', slide: 'solutions' }),
  });
  assert.equal((goto.body as { slide_id: string }).slide_id, 'solutions');

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

test('judge export keeps device rows and drops server-generated ones', async (t) => {
  const server: RunningServer = await startServer({ ADMIN_TOKEN: ADMIN, PING_INTERVAL_MS: '250' });
  t.after(() => server.stop());

  const screen = openWs(server.base, 'screen');
  const inbox = collect(screen);
  t.after(() => screen.close());
  await new Promise((r) => screen.once('open', r));
  await waitFor(inbox, (m) => m.type === 'snapshot', 'snapshot');

  // Without a phone, begin lands in fallback and demo.simulate invents lines
  // on the server. Those are exactly what must never reach the judges' file.
  const login = await fetch(server.base + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ token: ADMIN }),
  });
  const cookie = login.headers.get('set-cookie')?.split(';')[0] ?? '';
  const command = (payload: unknown) => json(server.base, '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify(payload),
  });

  await command({ type: 'demo.begin' });
  await waitFor(inbox, (m) => m.type === 'demo' && m.phase === 'fallback', 'fallback with no phone');

  await command({ type: 'demo.simulate', on: true });

  // Poll a value until it satisfies the predicate. Used here instead of the
  // screen socket because the guarantee under test is what the *export*
  // carries; the health counter only proves rows were generated at all.
  async function pollUntil<T>(read: () => Promise<T>, ok: (value: T) => boolean, label: string, timeout = 9000): Promise<T> {
    const deadline = Date.now() + timeout;
    let last: T | undefined;
    while (Date.now() < deadline) {
      last = await read();
      if (ok(last)) return last;
      await new Promise((r) => setTimeout(r, 120));
    }
    throw new Error(`timed out waiting for ${label}; last value ${JSON.stringify(last)}`);
  }

  // The simulator writes one tagged line every 3.2 s into the session.
  await pollUntil(
    async () => ((await json(server.base, '/v1/health')).body as { rows: number }).rows,
    (rows) => rows > 0,
    'a server-generated row stored in the session',
  );

  const duringSim = await json(server.base, '/v1/sessions/x/transcript');
  const simRows = (duringSim.body as { transcript: unknown[] }).transcript;
  assert.equal(simRows.length, 0, `server-generated rows leaked into the export: ${JSON.stringify(simRows)}`);

  // A device push over the phone socket is tagged `worker` and does belong.
  const phone = openWs(server.base, 'phone', '&device=phone_1');
  const inboxPhone = collect(phone);
  t.after(() => phone.close());
  await new Promise((r) => phone.once('open', r));
  phone.send(JSON.stringify({ type: 'hello', protocol: 'present-connect/1', models: ['zipformer-ru-int8'] }));
  await waitFor(inboxPhone, (m) => m.type === 'hello.ok', 'hello.ok');
  phone.send(JSON.stringify({
    type: 'transcript.final',
    utterance: { speaker_id: 'speaker_9', start_ms: 1000, end_ms: 2000, text: 'Реплика с устройства' },
  }));

  const exported = await pollUntil(
    async () => ((await json(server.base, '/v1/sessions/x/transcript')).body as {
      transcript: Array<{ text: string; speaker_id: string }>;
    }).transcript,
    (rows) => rows.length > 0,
    'the device row in the export',
    4000,
  );
  assert.equal(exported.length, 1, 'only the device row should survive');
  assert.equal(exported[0].text, 'Реплика с устройства');

  // Stored rows are strictly more numerous than exported ones while the
  // simulated lines exist: that difference is the whole provenance promise.
  const health = (await json(server.base, '/v1/health')).body as { rows: number };
  assert.ok(health.rows > exported.length, `expected stored ${health.rows} > exported ${exported.length}`);
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

test('session rotate clears transcript and resets demo', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const screen = openWs(server.base, 'screen');
  const inbox = collect(screen);
  t.after(() => screen.close());
  await new Promise((r) => screen.once('open', r));
  await waitFor(inbox, (m) => m.type === 'snapshot', 'snapshot');

  // Push a row first.
  await json(server.base, '/v1/transcript', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ transcript: [{ speaker_id: 'speaker_1', start_ms: 100, end_ms: 200, text: 'До ротации.' }] }),
  });
  await waitFor(inbox, (m) => m.type === 'transcript.final', 'row before rotate');

  const login = await fetch(server.base + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: ADMIN }),
  });
  const cookie = login.headers.get('set-cookie')?.split(';')[0] ?? '';
  const rotate = await json(server.base, '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'session.rotate' }),
  });
  assert.equal(rotate.status, 200);

  const cleared = await waitFor(inbox, (m) => m.type === 'transcript.clear', 'clear event');
  assert.ok(cleared);

  const exported = await json(server.base, '/v1/sessions/x/transcript');
  assert.deepEqual((exported.body as { transcript: unknown[] }).transcript, []);

  const newSession = (rotate.body as { session_id: string }).session_id;
  assert.match(newSession, /^sess_/);
});

test('the console releases and restores the audience scroll', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const screen = openWs(server.base, 'screen');
  const inbox = collect(screen);
  t.after(() => screen.close());
  await new Promise((r) => screen.once('open', r));
  const snapshot = await waitFor(inbox, (m) => m.type === 'snapshot', 'snapshot');
  assert.equal(snapshot.scroll_locked, true, 'a screen that comes up mid-talk must not drift');

  const login = await fetch(server.base + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: ADMIN }),
  });
  const cookie = login.headers.get('set-cookie')?.split(';')[0] ?? '';
  const command = (payload: unknown) => json(server.base, '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify(payload),
  });

  const release = await command({ type: 'screen.scroll', on: true });
  assert.equal(release.status, 200);
  assert.equal((release.body as { scroll_locked: boolean }).scroll_locked, false);
  assert.equal(
    (release.body as { slide_index: number }).slide_index,
    snapshot.slide_index,
    'releasing the scroll must not move the talk',
  );

  // The room is told twice on purpose: the broadcast for a live socket, the
  // checkpoint for the screens that dedupe by revision and would otherwise
  // drop a flag change delivered under the old one.
  await waitFor(inbox, (m) => m.type === 'screen.scroll' && m.on === true, 'screen.scroll');
  const unlocked = await waitFor(
    inbox,
    (m) => m.type === 'checkpoint' && m.scroll_locked === false,
    'checkpoint carrying the open lock',
  );
  assert.ok(
    Number(unlocked.revision) > Number(snapshot.revision),
    'the lock change must beat the screens’ revision dedupe',
  );

  const polled = await json(server.base, '/v1/presentation');
  assert.equal((polled.body as { scroll_locked: boolean }).scroll_locked, false);

  const relock = await command({ type: 'screen.scroll', on: false });
  assert.equal((relock.body as { scroll_locked: boolean }).scroll_locked, true);

  // Junk must not be coerced into a boolean.
  const junk = await command({ type: 'screen.scroll' });
  assert.equal(junk.status, 400);

  // A rotated session is a fresh run: locked again, even though the previous
  // one was released.
  await command({ type: 'session.rotate' });
  const afterRotate = await json(server.base, '/v1/presentation');
  assert.equal((afterRotate.body as { scroll_locked: boolean }).scroll_locked, true);
});

test('the console answering ping with pong is liveness, not a bad command', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN, PING_INTERVAL_MS: '200' });
  t.after(() => server.stop());

  const login = await fetch(server.base + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: ADMIN }),
  });
  const cookie = login.headers.get('set-cookie')?.split(';')[0] ?? '';

  const url = server.base.replace(/^http/, 'ws') + '/v1/present-connect?role=presenter';
  const ws = new WebSocket(url, { headers: { cookie } });
  t.after(() => ws.close());
  const inbox = collect(ws);
  await new Promise((r) => ws.once('open', r));

  // Like the console: every ping gets a pong, otherwise the heartbeat drops
  // the socket for staleness and the rest of the test talks to a closed pipe.
  ws.on('message', (raw) => {
    let message: AnyMessage;
    try { message = JSON.parse(String(raw)); } catch { return; }
    if (message.type === 'ping') ws.send(JSON.stringify({ type: 'pong', t: message.t }));
  });

  await waitFor(inbox, (m) => m.type === 'ping', 'ping');
  await new Promise((r) => setTimeout(r, 700));

  // Several ping rounds: the console replies to all of them, and none of those
  // replies may come back as a schema error — that is what the presenter saw
  // in the panel under the token field, one interval after login.
  assert.ok(
    inbox.filter((m) => m.type === 'ping').length >= 2,
    `expected more than one ping round, got ${inbox.map((m) => m.type).join(',')}`,
  );
  assert.deepEqual(inbox.filter((m) => m.type === 'error'), []);

  // The same socket still takes real commands.
  ws.send(JSON.stringify({ type: 'checkpoint.step', delta: 2 }));
  const ok = await waitFor(inbox, (m) => m.type === 'command.ok', 'command.ok');
  assert.equal(ok.slide_index, 2);
});

test('demo commands: simulate, fallback, end', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const screen = openWs(server.base, 'screen');
  const inbox = collect(screen);
  t.after(() => screen.close());
  await new Promise((r) => screen.once('open', r));
  await waitFor(inbox, (m) => m.type === 'snapshot', 'snapshot');

  const login = await fetch(server.base + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: ADMIN }),
  });
  const cookie = login.headers.get('set-cookie')?.split(';')[0] ?? '';

  // Enable simulate
  const sim = await json(server.base, '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'demo.simulate', on: true }),
  });
  assert.equal(sim.status, 200);
  const simEvent = await waitFor(inbox, (m) => m.type === 'simulate', 'simulate event');
  assert.equal(simEvent.on, true);

  // Begin demo without phone -> fallback
  const begin = await json(server.base, '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'demo.begin' }),
  });
  assert.equal(begin.status, 200);
  const fallback = await waitFor(inbox, (m) => m.type === 'demo' && m.phase === 'fallback', 'fallback');
  assert.equal(fallback.reason, 'phone_offline_at_begin');

  // Simulated rows should appear
  const simRow = await waitFor(inbox, (m) => m.type === 'transcript.final', 'simulated row', 10_000);
  assert.equal((simRow.row as { source: string }).source, 'simulated');

  // Manual fallback
  const manual = await json(server.base, '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'demo.fallback' }),
  });
  assert.equal(manual.status, 200);

  // End demo
  const end = await json(server.base, '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'demo.end' }),
  });
  assert.equal(end.status, 200);
  const idle = await waitFor(inbox, (m) => m.type === 'demo' && m.phase === 'idle', 'idle');
  assert.equal(idle.reason, 'demo_end');
});

test('phone status and partial transcript are relayed', async (t) => {
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

  phone.send(JSON.stringify({ type: 'hello', protocol: 'present-connect/1', models: ['zipformer-ru-int8'] }));
  await waitFor(inboxPhone, (m) => m.type === 'hello.ok', 'hello.ok');

  // Phone status — wait for the message where recording is true
  // (the first 'phone' message comes from hello, before status is set)
  phone.send(JSON.stringify({ type: 'phone.status', recording: true, processing: false, engine: 'zipformer-ru-int8' }));
  const status = await waitFor(inbox, (m) => m.type === 'phone' && m.recording === true, 'phone status with recording');
  assert.equal(status.recording, true);
  assert.equal(status.engine, 'zipformer-ru-int8');

  // Partial transcript
  phone.send(JSON.stringify({
    type: 'transcript.partial',
    stream_id: 's02',
    speaker_id: 'speaker_2',
    start_ms: 1000,
    end_ms: 2000,
    text: 'частичный текст',
  }));
  const partial = await waitFor(inbox, (m) => m.type === 'transcript.partial', 'partial');
  assert.equal((partial.row as { text: string }).text, 'частичный текст');
  assert.equal((partial.row as { partial: boolean }).partial, true);
});

test('BAD_PROTOCOL is rejected', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const phone = openWs(server.base, 'phone');
  const inboxPhone = collect(phone);
  t.after(() => phone.close());
  await new Promise((r) => phone.once('open', r));

  phone.send(JSON.stringify({ type: 'hello', protocol: 'wrong/1', models: ['zipformer-ru-int8'] }));

  // The server must close the socket (with or without an error message first).
  await new Promise<void>((resolve) => {
    if (phone.readyState === phone.CLOSED) { resolve(); return; }
    const timer = setTimeout(() => resolve(), 5000);
    phone.once('close', () => { clearTimeout(timer); resolve(); });
  });
  assert.equal(phone.readyState, phone.CLOSED, 'socket must be closed after bad protocol');
});

test('PHONE_TOKEN protects ingest endpoints', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN, PHONE_TOKEN: 'phone-secret' });
  t.after(() => server.stop());

  // Without token -> 401
  const denied = await json(server.base, '/v1/transcript', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ transcript: [{ speaker_id: 'speaker_1', start_ms: 1, end_ms: 2, text: 'x' }] }),
  });
  assert.equal(denied.status, 401);

  // With token -> 200
  const ok = await json(server.base, '/v1/transcript', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bearer phone-secret' },
    body: JSON.stringify({ transcript: [{ speaker_id: 'speaker_1', start_ms: 1, end_ms: 2, text: 'x' }] }),
  });
  assert.equal(ok.status, 200);
});

test('logout clears the presenter cookie', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const login = await fetch(server.base + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: ADMIN }),
  });
  const cookie = login.headers.get('set-cookie')?.split(';')[0] ?? '';

  // Logged in -> presenter true (must pass the cookie)
  const state1 = await fetch(server.base + '/api/state', { headers: { cookie } });
  const state1Body = await state1.json() as { presenter: boolean };
  assert.equal(state1Body.presenter, true);

  // Logout
  const logout = await fetch(server.base + '/api/logout', {
    method: 'POST', headers: { cookie },
  });
  assert.equal(logout.status, 200);
  const logoutCookie = logout.headers.get('set-cookie') ?? '';
  assert.match(logoutCookie, /Max-Age=0/);
});

test('invite endpoint returns join URL', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const invite = await json(server.base, '/api/invite');
  assert.equal(invite.status, 200);
  const body = invite.body as { join_url: string; session_id: string; slides: string[] };
  assert.match(body.join_url, /\/join\?session=/);
  assert.match(body.session_id, /^sess_/);
  assert.equal(body.slides.length, 8);
});

test('workers register returns ws_url', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const reg = await json(server.base, '/v1/workers/register', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ device_id: 'phone_test' }),
  });
  assert.equal(reg.status, 200);
  const body = reg.body as { worker_id: string; ws_url: string; session_id: string };
  assert.equal(body.worker_id, 'phone_test');
  assert.match(body.ws_url, /role=phone/);
  assert.match(body.ws_url, /device=phone_test/);
});
