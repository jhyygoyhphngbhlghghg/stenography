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

async function login(base: string): Promise<string> {
  const response = await fetch(base + '/api/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ token: ADMIN }),
  });
  return response.headers.get('set-cookie')?.split(';')[0] ?? '';
}

test('every HTTP response carries CORS headers and OPTIONS preflight is answered', async (t) => {
  const server: RunningServer = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  // Preflight: 204, no body, CORS headers present.
  const preflight = await fetch(server.base + '/v1/frontend/checkpoint', {
    method: 'OPTIONS',
    headers: { origin: 'http://localhost:3000', 'access-control-request-method': 'GET' },
  });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers.get('access-control-allow-origin'), '*');
  assert.equal(preflight.headers.get('access-control-allow-methods'), 'GET, POST, OPTIONS');
  assert.equal(preflight.headers.get('access-control-allow-headers'), 'Content-Type, Authorization');
  assert.equal((await preflight.text()).length, 0);

  // Preflight is answered before routing: even an unknown path returns 204.
  const unknownPreflight = await fetch(server.base + '/v1/does-not-exist', { method: 'OPTIONS' });
  assert.equal(unknownPreflight.status, 204);
  assert.equal(unknownPreflight.headers.get('access-control-allow-origin'), '*');

  // JSON endpoints: GET and POST alike, including error responses.
  for (const path of ['/v1/frontend/checkpoint', '/v1/presentation', '/v1/health', '/api/invite']) {
    const response = await fetch(server.base + path);
    assert.equal(response.headers.get('access-control-allow-origin'), '*', `${path} missing CORS`);
    assert.equal(response.headers.get('access-control-allow-methods'), 'GET, POST, OPTIONS');
    assert.equal(response.headers.get('access-control-allow-headers'), 'Content-Type, Authorization');
    await response.arrayBuffer();
  }

  const unauthorized = await fetch(server.base + '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ type: 'demo.begin' }),
  });
  assert.equal(unauthorized.status, 401);
  assert.equal(unauthorized.headers.get('access-control-allow-origin'), '*', 'errors must be CORS-readable');
  await unauthorized.arrayBuffer();

  // Static assets served by serveStatic() carry CORS too.
  const page = await fetch(server.base + '/');
  assert.equal(page.status, 200);
  assert.equal(page.headers.get('access-control-allow-origin'), '*');
  await page.arrayBuffer();

  // Wildcard origin must not enable credentials.
  assert.equal(page.headers.get('access-control-allow-credentials'), null);
});

test('/v1/frontend/checkpoint returns the checkpoint with slide_label and dialogue rows', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const initial = await fetch(server.base + '/v1/frontend/checkpoint');
  assert.equal(initial.status, 200);
  const first = (await initial.json()) as Record<string, unknown>;
  assert.equal(first.slide_id, 'title');
  assert.equal(first.slide_label, 'QR');
  assert.deepEqual(first.transcript, []);
  assert.equal(first.banner, null);
  assert.ok(Array.isArray(first.slides) && first.slides.length === 9);

  // Push two rows over HTTP ingest, then move the presenter to another slide.
  const push = await fetch(server.base + '/v1/transcript', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ transcript: [
      { speaker_id: 'speaker_1', start_ms: 800, end_ms: 6400, text: 'Первое место говорит.' },
      { speaker_id: 'speaker_4', start_ms: 6500, end_ms: 9000, text: 'Четвёртое отвечает.' },
    ] }),
  });
  assert.equal(push.status, 200);
  await push.arrayBuffer();

  const cookie = await login(server.base);
  const goto = await fetch(server.base + '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'checkpoint.goto', slide: 'on-device' }),
  });
  assert.equal(goto.status, 200);
  const gotoBody = (await goto.json()) as { slide_label: string };
  // checkpointPayload carries the label everywhere, so presenter replies have it too.
  assert.equal(gotoBody.slide_label, 'Приватность');

  const response = await fetch(server.base + '/v1/frontend/checkpoint');
  const body = (await response.json()) as {
    slide_id: string; slide_label: string; slide_index: number;
    transcript: Array<Record<string, unknown>>;
  };
  assert.equal(body.slide_id, 'on-device');
  assert.equal(body.slide_label, 'Приватность');
  assert.equal(body.slide_index, 4);
  assert.equal(body.transcript.length, 2);
  // Dialogue schema: exactly the four fields the React screen validates.
  for (const row of body.transcript) {
    assert.deepEqual(Object.keys(row).sort(), ['end_ms', 'speaker_id', 'start_ms', 'text']);
  }
  assert.deepEqual(
    body.transcript.map((r) => r.speaker_id),
    ['speaker_1', 'speaker_4'],
    'rows stay in arrival order',
  );

  // Same slide_id as the existing endpoint, plus the label.
  const poll = await (await fetch(server.base + '/v1/presentation')).json() as Record<string, unknown>;
  assert.equal(poll.slide_index, body.slide_index);
  assert.equal(poll.slide_label, 'Приватность');

  // Every label in the deck resolves, and none is an empty string.
  const labels: Record<string, string> = {
    title: 'QR', problem: 'Проблема', approach: 'Подход', pipeline: 'Конвейер',
    'on-device': 'Приватность', live: 'Живое демо', resilience: 'Отказоустойчивость',
    numbers: 'Цифры', team: 'Команда',
  };
  for (const [slideId, expected] of Object.entries(labels)) {
    const moved = await fetch(server.base + '/api/command', {
      method: 'POST', headers: { 'content-type': 'application/json', cookie },
      body: JSON.stringify({ type: 'checkpoint.goto', slide: slideId }),
    });
    const movedBody = (await moved.json()) as { slide_label: string; slide_id: string };
    assert.equal(movedBody.slide_id, slideId);
    assert.equal(movedBody.slide_label, expected, `label for ${slideId}`);
  }
});

test('a role=screen socket gets slide_label and dialogue rows in its snapshot', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  // Seed a row first so the snapshot has a transcript to carry.
  const push = await fetch(server.base + '/v1/transcript', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ transcript: [{ speaker_id: 'speaker_2', start_ms: 10, end_ms: 900, text: 'Второе место.' }] }),
  });
  assert.equal(push.status, 200);
  await push.arrayBuffer();

  const screen = new WebSocket(server.base.replace(/^http/, 'ws') + '/v1/present-connect?role=screen');
  const inbox = collect(screen);
  t.after(() => screen.close());
  await new Promise((r) => screen.once('open', r));

  const snapshot = await waitFor(inbox, (m) => m.type === 'snapshot', 'screen snapshot');
  assert.equal(snapshot.slide_id, 'title');
  assert.equal(snapshot.slide_label, 'QR');
  const rows = snapshot.transcript as Array<Record<string, unknown>>;
  assert.equal(rows.length, 1);
  assert.deepEqual(Object.keys(rows[0]).sort(), ['end_ms', 'speaker_id', 'start_ms', 'text']);
  assert.equal(rows[0].text, 'Второе место.');

  // Live checkpoint broadcasts carry the label as the presenter moves.
  const cookie = await login(server.base);
  const live = await fetch(server.base + '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'checkpoint.goto', slide: 'live' }),
  });
  assert.equal(live.status, 200);
  await live.arrayBuffer();

  const checkpoint = await waitFor(inbox, (m) => m.type === 'checkpoint', 'live checkpoint');
  assert.equal(checkpoint.slide_id, 'live');
  assert.equal(checkpoint.slide_label, 'Живое демо');

  // Later rows still arrive one at a time for the incremental feed.
  const second = await fetch(server.base + '/v1/transcript', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ transcript: [{ speaker_id: 'speaker_5', start_ms: 950, end_ms: 2000, text: 'Пятое место.' }] }),
  });
  assert.equal(second.status, 200);
  await second.arrayBuffer();

  const row = await waitFor(inbox, (m) => m.type === 'transcript.final', 'incremental row');
  assert.equal((row.row as { speaker_id: string }).speaker_id, 'speaker_5');

  // The snapshot is capped so a long session cannot ship an unbounded payload.
  const capped = await fetch(server.base + '/v1/frontend/checkpoint');
  const cappedBody = (await capped.json()) as { transcript: unknown[] };
  assert.ok(cappedBody.transcript.length <= 60, `snapshot transcript capped, got ${cappedBody.transcript.length}`);
});

test('fallback snapshots still show the dialogue with a banner', async (t) => {
  const server = await startServer({ ADMIN_TOKEN: ADMIN });
  t.after(() => server.stop());

  const phone = new WebSocket(server.base.replace(/^http/, 'ws') + '/v1/present-connect?role=phone&device=phone_1');
  const inboxPhone = collect(phone);
  t.after(() => phone.close());
  await new Promise((r) => phone.once('open', r));
  phone.send(JSON.stringify({ type: 'hello', protocol: 'present-connect/1', models: ['zipformer-ru-int8'] }));
  await waitFor(inboxPhone, (m) => m.type === 'hello.ok', 'hello.ok');

  const cookie = await login(server.base);
  const begin = await fetch(server.base + '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'demo.begin' }),
  });
  assert.equal(begin.status, 200);
  await begin.arrayBuffer();

  // Lose the phone: the server switches to reserve rows.
  phone.terminate();
  await new Promise((r) => setTimeout(r, 300));

  const response = await fetch(server.base + '/v1/frontend/checkpoint');
  const body = (await response.json()) as {
    demo_phase: string; banner: string | null; transcript: Array<Record<string, unknown>>;
  };
  assert.equal(body.demo_phase, 'fallback');
  assert.ok(typeof body.banner === 'string' && body.banner.length > 0, 'fallback must show a banner');
  assert.equal(body.transcript.length, 6, 'reserve dialogue is visible to the frontend');
  for (const row of body.transcript) {
    assert.deepEqual(Object.keys(row).sort(), ['end_ms', 'speaker_id', 'start_ms', 'text']);
    assert.ok(typeof row.text === 'string' && row.text.length > 0);
  }
});
