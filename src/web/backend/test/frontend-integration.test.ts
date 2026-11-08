import assert from 'node:assert/strict';
import test from 'node:test';
import WebSocket from 'ws';
import { SLIDES, SLIDE_LABELS } from '../src/config.ts';
import { startServer, type RunningServer } from './harness.ts';

/** First slide of the current deck — the deck itself is owned by config.ts. */
const FIRST_SLIDE = SLIDES[0]!;
const FIRST_LABEL = SLIDE_LABELS[FIRST_SLIDE]!;

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
  assert.equal(first.slide_id, FIRST_SLIDE);
  assert.equal(first.slide_label, FIRST_LABEL);
  assert.deepEqual(first.transcript, []);
  assert.equal(first.banner, null);
  assert.deepEqual(first.slides, [...SLIDES], 'checkpoint echoes the configured deck');

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
    body: JSON.stringify({ type: 'checkpoint.goto', slide: 'solutions' }),
  });
  assert.equal(goto.status, 200);
  const gotoBody = (await goto.json()) as { slide_label: string };
  // checkpointPayload carries the label everywhere, so presenter replies have it too.
  assert.equal(gotoBody.slide_label, SLIDE_LABELS.solutions);

  const response = await fetch(server.base + '/v1/frontend/checkpoint');
  const body = (await response.json()) as {
    slide_id: string; slide_label: string; slide_index: number;
    transcript: Array<Record<string, unknown>>;
  };
  assert.equal(body.slide_id, 'solutions');
  assert.equal(body.slide_label, SLIDE_LABELS.solutions);
  assert.equal(body.slide_index, SLIDES.indexOf('solutions'));
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
  assert.equal(poll.slide_label, SLIDE_LABELS.solutions);

  // Every slide in the deck resolves to a non-empty human-readable label.
  for (const [slideId, expected] of Object.entries(SLIDE_LABELS)) {
    assert.ok(expected.trim().length > 0, `${slideId} needs a label`);
    assert.ok(SLIDES.includes(slideId as (typeof SLIDES)[number]), `${slideId} is not in the deck`);
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
  assert.equal(snapshot.slide_id, FIRST_SLIDE);
  assert.equal(snapshot.slide_label, FIRST_LABEL);
  const rows = snapshot.transcript as Array<Record<string, unknown>>;
  assert.equal(rows.length, 1);
  assert.equal(rows[0].text, 'Второе место.');
  // Superset of the dialogue schema: the four required fields are present, and
  // provenance stays for the built-in deck (it labels reserve rows «запись»).
  for (const field of ['speaker_id', 'text', 'start_ms', 'end_ms']) {
    assert.ok(field in rows[0], `snapshot row must carry ${field}`);
  }
  assert.equal(rows[0].source, 'phone', 'provenance is preserved on the socket');

  // Live checkpoint broadcasts carry the label as the presenter moves.
  const cookie = await login(server.base);
  const live = await fetch(server.base + '/api/command', {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'checkpoint.goto', slide: 'demo' }),
  });
  assert.equal(live.status, 200);
  await live.arrayBuffer();

  const checkpoint = await waitFor(inbox, (m) => m.type === 'checkpoint', 'live checkpoint');
  assert.equal(checkpoint.slide_id, 'demo');
  assert.equal(checkpoint.slide_label, SLIDE_LABELS.demo);

  // Later rows still arrive one at a time for the incremental feed.
  const second = await fetch(server.base + '/v1/transcript', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ transcript: [{ speaker_id: 'speaker_5', start_ms: 950, end_ms: 2000, text: 'Пятое место.' }] }),
  });
  assert.equal(second.status, 200);
  await second.arrayBuffer();

  const row = await waitFor(inbox, (m) => m.type === 'transcript.final', 'incremental row');
  assert.equal((row.row as { speaker_id: string }).speaker_id, 'speaker_5');

  // The frontend's non-strict zod dialogue schema accepts these rows as-is.
  const dialogueOnly = rows.map(({ speaker_id, text, start_ms, end_ms }) => ({ speaker_id, text, start_ms, end_ms }));
  assert.deepEqual(Object.keys(dialogueOnly[0]).sort(), ['end_ms', 'speaker_id', 'start_ms', 'text']);

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
