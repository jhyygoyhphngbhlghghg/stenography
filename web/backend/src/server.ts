import http from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { WebSocketServer, type WebSocket, type RawData } from 'ws';
import { SLIDES, config } from './config.js';
import {
  IngestStatusSchema,
  IngestTranscriptSchema,
  PresenterCommandSchema,
  WorkerMessageSchema,
  safeParse,
  type TranscriptRow,
} from './protocol.js';
import {
  checkpointPayload,
  constantTimeEqual,
  createSession,
  gotoSlide,
  phonePayload,
  pushTranscript,
  replaceTranscript,
  rotateSession,
  seatFromSpeakerId,
  sortBrief,
  stepSlide,
  streamIdForSeat,
  type SessionState,
} from './store.js';
import { RESERVE_BANNER, RESERVE_ROWS, reserveTranscript, simulatedLine } from './fallback.js';

/* ------------------------------------------------------------------ *
 * Session
 * ------------------------------------------------------------------ */

const session: SessionState = createSession();

type Role = 'phone' | 'screen' | 'presenter';

interface Sock {
  id: string;
  ws: WebSocket;
  role: Role;
  alive: boolean;
  lastSeenAt: number;
  deviceId?: string;
}

const sockets = new Set<Sock>();
let phoneSock: Sock | null = null;
let simTimer: NodeJS.Timeout | null = null;
let simIndex = 0;

/* ------------------------------------------------------------------ *
 * Transport helpers
 * ------------------------------------------------------------------ */

function send(ws: WebSocket, payload: unknown): void {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(payload));
}

function toBuffer(raw: RawData): Buffer {
  if (Buffer.isBuffer(raw)) return raw;
  if (Array.isArray(raw)) return Buffer.concat(raw);
  if (raw instanceof ArrayBuffer) return Buffer.from(raw);
  const view = raw as ArrayBufferView;
  return Buffer.from(view.buffer, view.byteOffset, view.byteLength);
}

/** Screens follow the checkpoint; the presenter console mirrors it too. */
function broadcastScreens(payload: unknown): void {
  for (const s of sockets) {
    if (s.role === 'screen' || s.role === 'presenter') send(s.ws, payload);
  }
}

function broadcastCheckpoint(): void {
  broadcastScreens({ type: 'checkpoint', ...checkpointPayload(session) });
}

function broadcastTranscriptRow(row: TranscriptRow): void {
  broadcastScreens({ type: 'transcript.final', row });
}

/* ------------------------------------------------------------------ *
 * Demo phase + fallback
 * ------------------------------------------------------------------ */

function setDemoPhase(phase: SessionState['demo']['phase'], reason: string): void {
  const changed = session.demo.phase !== phase || session.demo.reason !== reason;
  session.demo.phase = phase;
  session.demo.reason = reason;

  if (phase === 'fallback') {
    // Reserve rows replace the live feed so the stage layout stays intact.
    const reserve = reserveTranscript();
    replaceTranscript(session, reserve);
    broadcastScreens({ type: 'transcript.replay', rows: reserve, banner: RESERVE_BANNER });
  }

  broadcastScreens({ type: 'demo', phase, reason, banner: phase === 'fallback' ? RESERVE_BANNER : null });
  if (changed) broadcastCheckpoint();
  syncSim();
}

function enterFallbackIfLive(reason: string): void {
  if (session.demo.phase === 'live') setDemoPhase('fallback', reason);
}

/** The phone came back: leave reserve mode only if we were in it for that reason. */
function maybeBackToLive(reason: string): void {
  if (session.demo.phase !== 'fallback') return;
  const phoneCaused = session.demo.reason === 'phone_disconnected' || session.demo.reason === 'phone_stall' || session.demo.reason === 'phone_offline_at_begin';
  if (!phoneCaused) return;
  setDemoPhase('live', reason);
}

/* ------------------------------------------------------------------ *
 * Simulator (rehearsal only, explicitly tagged)
 * ------------------------------------------------------------------ */

function stopSim(): void {
  if (simTimer) clearInterval(simTimer);
  simTimer = null;
}

function startSim(): void {
  stopSim();
  simTimer = setInterval(() => {
    const line = simulatedLine(simIndex);
    simIndex += 1;
    const now = Date.now();
    const row: TranscriptRow = {
      stream_id: streamIdForSeat(line.seat),
      seat: line.seat,
      speaker_id: `speaker_${line.seat}`,
      start_ms: now - 2600,
      end_ms: now,
      text: line.text,
      source: 'simulated',
      received_at: now,
    };
    pushTranscript(session, row);
    broadcastTranscriptRow(row);
  }, 3200);
  simTimer.unref?.();
}

function syncSim(): void {
  const want = session.demo.simulate && session.demo.phase !== 'idle' && !session.phone.connected;
  if (want && !simTimer) startSim();
  else if (!want && simTimer) stopSim();
}

/* ------------------------------------------------------------------ *
 * Phone status helpers
 * ------------------------------------------------------------------ */

function markPhoneSeen(): void {
  session.phone.lastSeenAt = Date.now();
}

function publishPhoneState(): void {
  broadcastScreens({ type: 'phone', ...phonePayload(session) });
}

function phoneLost(reason: string): void {
  session.phone.connected = false;
  session.phone.recording = false;
  session.phone.processing = false;
  session.phone.deviceId = null;
  session.phone.stub = false;
  phoneSock = null;
  publishPhoneState();
  enterFallbackIfLive(reason);
}

/**
 * Rows from the phone. `reset` starts a fresh run so re-transcribing the same
 * recording does not stack duplicates on the projector.
 */
function ingestUtterances(
  rows: Array<{ speaker_id: string; start_ms: number; end_ms: number; text: string }>,
  opts: { reset?: boolean; engine?: string; source: 'phone' | 'worker' },
): TranscriptRow[] {
  const now = Date.now();
  if (opts.reset) replaceTranscript(session, []);
  if (opts.engine) session.phone.engine = opts.engine;
  session.phone.lastPushAt = now;
  session.phone.rowsPushed += rows.length;
  markPhoneSeen();

  const out: TranscriptRow[] = [];
  for (const u of rows) {
    const seat = seatFromSpeakerId(u.speaker_id);
    const row: TranscriptRow = {
      speaker_id: u.speaker_id,
      start_ms: u.start_ms,
      end_ms: u.end_ms,
      text: u.text,
      stream_id: seat ? streamIdForSeat(seat) : null,
      seat,
      source: opts.source,
      received_at: now,
    };
    pushTranscript(session, row);
    out.push(row);
  }
  publishPhoneState();
  return out;
}

/* ------------------------------------------------------------------ *
 * Phone messages over WebSocket (present-connect/1)
 * ------------------------------------------------------------------ */

function handlePhoneMessage(sock: Sock, raw: string): void {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    send(sock.ws, { type: 'error', code: 'BAD_JSON', message: 'invalid json' });
    return;
  }

  const result = safeParse(WorkerMessageSchema, parsed);
  if (!result.ok) {
    send(sock.ws, { type: 'error', code: 'BAD_MESSAGE', message: result.error });
    return;
  }

  const msg = result.data;
  markPhoneSeen();
  sock.alive = true;
  sock.lastSeenAt = Date.now();

  switch (msg.type) {
    case 'hello': {
      const models = msg.models ?? [];
      const stub = models.length === 0;
      if (phoneSock && phoneSock !== sock && phoneSock.ws.readyState === phoneSock.ws.OPEN) {
        send(phoneSock.ws, { type: 'error', code: 'SUPERSEDED', message: 'another phone took over' });
        phoneSock.ws.close(4003, 'superseded');
      }
      phoneSock = sock;
      sock.deviceId = msg.protocol ? `phone_${sock.id}` : `phone_${sock.id}`;
      session.phone.connected = true;
      session.phone.deviceId = sock.deviceId;
      session.phone.engine = models[0] ?? session.phone.engine;
      session.phone.stub = stub;
      send(sock.ws, {
        type: 'hello.ok',
        session_id: session.id,
        worker_id: sock.deviceId,
        protocol: config.protocol,
        slides: SLIDES,
        checkpoint: checkpointPayload(session),
      });
      publishPhoneState();
      // A stub build has no models on device: docs forbid using it on stage.
      if (stub) enterFallbackIfLive('phone_stub_models_missing');
      else maybeBackToLive('phone_reconnected');
      syncSim();
      break;
    }

    case 'pong':
      sock.alive = true;
      break;

    case 'phone.status': {
      if (msg.recording !== undefined) session.phone.recording = msg.recording;
      if (msg.processing !== undefined) session.phone.processing = msg.processing;
      if (msg.engine !== undefined) session.phone.engine = msg.engine;
      if (msg.note !== undefined) session.phone.note = msg.note;
      publishPhoneState();
      break;
    }

    case 'transcript.final': {
      const rows = ingestUtterances([msg.utterance], { engine: session.phone.engine ?? undefined, source: 'worker' });
      for (const row of rows) broadcastTranscriptRow(row);
      break;
    }

    case 'transcript.partial': {
      const seat = seatFromSpeakerId(msg.speaker_id);
      broadcastScreens({
        type: 'transcript.partial',
        row: {
          speaker_id: msg.speaker_id,
          start_ms: msg.start_ms,
          end_ms: msg.end_ms,
          text: msg.text,
          stream_id: msg.stream_id ?? (seat ? streamIdForSeat(seat) : null),
          seat,
          partial: true,
          source: 'worker',
          received_at: Date.now(),
        } satisfies TranscriptRow,
      });
      break;
    }

    case 'error': {
      if (msg.code === 'MODELS_MISSING') {
        session.phone.stub = true;
        publishPhoneState();
        enterFallbackIfLive('phone_stub_models_missing');
      }
      broadcastScreens({ type: 'phone.error', code: msg.code, message: msg.message });
      break;
    }
  }
}

/* ------------------------------------------------------------------ *
 * Presenter commands
 * ------------------------------------------------------------------ */

function applyPresenterCommand(cmd: unknown, reply: (payload: unknown) => void): boolean {
  const result = safeParse(PresenterCommandSchema, cmd);
  if (!result.ok) {
    reply({ type: 'error', code: 'BAD_COMMAND', message: result.error });
    return false;
  }
  const c = result.data;
  try {
    switch (c.type) {
      case 'checkpoint.set':
        gotoSlide(session, c.index, c.t_ms ?? 0);
        break;
      case 'checkpoint.step':
        stepSlide(session, c.delta);
        break;
      case 'checkpoint.goto':
        gotoSlide(session, c.slide);
        break;
      case 'demo.begin':
        session.demo.startedAt = Date.now();
        if (!session.phone.connected) setDemoPhase('fallback', 'phone_offline_at_begin');
        else if (session.phone.stub) setDemoPhase('fallback', 'phone_stub_models_missing');
        else setDemoPhase('live', 'demo_begin');
        break;
      case 'demo.end':
        stopSim();
        session.demo.startedAt = null;
        session.demo.phase = 'idle';
        session.demo.reason = 'demo_end';
        broadcastScreens({ type: 'demo', phase: 'idle', reason: 'demo_end', banner: null });
        break;
      case 'demo.fallback':
        setDemoPhase('fallback', 'manual');
        break;
      case 'demo.simulate':
        session.demo.simulate = c.on;
        broadcastScreens({ type: 'simulate', on: c.on });
        break;
      case 'transcript.clear':
        replaceTranscript(session, []);
        broadcastScreens({ type: 'transcript.clear' });
        break;
      case 'session.rotate':
        rotateSession(session);
        broadcastScreens({ type: 'transcript.clear' });
        break;
    }
  } catch (error) {
    reply({ type: 'error', code: 'BAD_COMMAND', message: error instanceof Error ? error.message : 'rejected' });
    return false;
  }
  broadcastCheckpoint();
  syncSim();
  reply({ type: 'command.ok', command: c.type, ...checkpointPayload(session) });
  return true;
}

/* ------------------------------------------------------------------ *
 * HTTP surface
 * ------------------------------------------------------------------ */

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.wasm': 'application/wasm',
};

const SECURITY_HEADERS: Record<string, string> = {
  'x-content-type-options': 'nosniff',
  'x-frame-options': 'DENY',
  'referrer-policy': 'no-referrer',
  'permissions-policy': 'microphone=(), camera=(), geolocation=()',
  'content-security-policy': [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    "connect-src 'self' ws: wss:",
    "media-src 'self' blob:",
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; '),
};

function json(res: http.ServerResponse, status: number, body: unknown, extra: Record<string, string> = {}): void {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': Buffer.byteLength(payload),
    ...SECURITY_HEADERS,
    ...extra,
  });
  res.end(payload);
}

function readBody(req: http.IncomingMessage, limit = config.maxBodyBytes): Promise<string> {
  return new Promise((resolvePromise, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', (chunk: Buffer) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolvePromise(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function cookieValue(req: http.IncomingMessage, name: string): string | null {
  const header = req.headers.cookie;
  if (!header) return null;
  for (const part of header.split(';')) {
    const [k, ...rest] = part.split('=');
    if (k?.trim() === name) {
      try {
        return decodeURIComponent(rest.join('=').trim());
      } catch {
        return rest.join('=').trim();
      }
    }
  }
  return null;
}

function bearer(req: http.IncomingMessage): string | null {
  const auth = req.headers.authorization;
  return auth?.startsWith('Bearer ') ? auth.slice(7) : null;
}

function isPresenter(req: http.IncomingMessage): boolean {
  if (!config.adminToken) return false;
  const cookie = cookieValue(req, 'presenter');
  if (cookie && constantTimeEqual(cookie, config.adminToken)) return true;
  const token = bearer(req);
  return Boolean(token && constantTimeEqual(token, config.adminToken));
}

function isPhone(req: http.IncomingMessage): boolean {
  if (!config.phoneToken) return true; // loopback-only by default; token optional
  const token = cookieValue(req, 'phone') ?? bearer(req);
  return Boolean(token && constantTimeEqual(token, config.phoneToken));
}

const attempts = new Map<string, { count: number; resetAt: number }>();

function throttled(key: string): boolean {
  const now = Date.now();
  const entry = attempts.get(key);
  if (!entry || entry.resetAt < now) {
    attempts.set(key, { count: 1, resetAt: now + 10 * 60_000 });
    return false;
  }
  entry.count += 1;
  return entry.count > 8;
}

async function serveStatic(req: http.IncomingMessage, res: http.ServerResponse, pathname: string): Promise<void> {
  const root = resolve(config.publicDir);
  const rel = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const target = normalize(join(root, rel));
  if (target !== root && !target.startsWith(root + sep)) {
    json(res, 403, { error: 'forbidden' });
    return;
  }

  const sendFile = async (file: string, type: string): Promise<boolean> => {
    try {
      const st = await stat(file);
      if (!st.isFile()) return false;
      const body = await readFile(file);
      res.writeHead(200, {
        'content-type': type,
        'cache-control': 'no-store',
        'content-length': body.length,
        ...SECURITY_HEADERS,
      });
      res.end(req.method === 'HEAD' ? undefined : body);
      return true;
    } catch {
      return false;
    }
  };

  const type = MIME[extname(target).toLowerCase()] ?? 'application/octet-stream';
  if (await sendFile(target, type)) return;

  const pageMap: Record<string, string> = {
    '/join': 'index.html',
    '/stage': 'index.html',
    '/presenter': 'presenter.html',
  };
  const mapped = pageMap[pathname];
  if (mapped && (await sendFile(join(root, mapped), 'text/html; charset=utf-8'))) return;

  if (await sendFile(join(root, 'index.html'), 'text/html; charset=utf-8')) return;
  json(res, 404, { error: 'not_found' });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  const path = url.pathname;
  const method = req.method ?? 'GET';

  try {
    /* ---- checkpoint polling (contract §5, web-only) ---- */
    if (path === '/v1/presentation' && method === 'GET') {
      json(res, 200, checkpointPayload(session));
      return;
    }
    if (/^\/v1\/presentation\/[^/]+\/checkpoint$/.test(path) && method === 'GET') {
      json(res, 200, checkpointPayload(session));
      return;
    }

    /* ---- transcript export: brief JSON, four fields only ---- */
    if (/^\/v1\/sessions\/[^/]+\/transcript$/.test(path) && method === 'GET') {
      json(res, 200, { transcript: sortBrief(session.transcript) });
      return;
    }

    /* ---- phone ingest (HTTP, no WebSocket needed on the app side) ---- */
    if (path === '/v1/transcript' && method === 'POST') {
      if (!isPhone(req)) {
        json(res, 401, { error: 'unauthorized' });
        return;
      }
      const body = await readBody(req).catch(() => '');
      let parsed: unknown;
      try {
        parsed = JSON.parse(body);
      } catch {
        json(res, 400, { error: 'bad_json' });
        return;
      }
      const result = safeParse(IngestTranscriptSchema, parsed);
      if (!result.ok) {
        json(res, 400, { error: 'invalid_transcript', detail: result.error });
        return;
      }
      const payload = result.data;
      // The app is authoritative: it owns mic + models. A push implies a live source.
      session.phone.connected = true;
      session.phone.stub = false;
      markPhoneSeen();
      const rows = ingestUtterances(payload.transcript, {
        reset: payload.reset,
        engine: payload.engine,
        source: 'phone',
      });
      for (const row of rows) broadcastTranscriptRow(row);
      if (session.demo.phase === 'idle') setDemoPhase('live', 'phone_push');
      else maybeBackToLive('phone_push');
      json(res, 200, { ok: true, accepted: rows.length, ...checkpointPayload(session) });
      return;
    }

    if (path === '/v1/status' && method === 'POST') {
      if (!isPhone(req)) {
        json(res, 401, { error: 'unauthorized' });
        return;
      }
      const body = await readBody(req, 4096).catch(() => '');
      let parsed: unknown;
      try {
        parsed = JSON.parse(body);
      } catch {
        json(res, 400, { error: 'bad_json' });
        return;
      }
      const result = safeParse(IngestStatusSchema, parsed);
      if (!result.ok) {
        json(res, 400, { error: 'invalid_status', detail: result.error });
        return;
      }
      const s = result.data;
      if (s.recording !== undefined) session.phone.recording = s.recording;
      if (s.processing !== undefined) session.phone.processing = s.processing;
      if (s.engine !== undefined) session.phone.engine = s.engine;
      if (s.note !== undefined) session.phone.note = s.note;
      session.phone.connected = true;
      markPhoneSeen();
      publishPhoneState();
      json(res, 200, { ok: true, ...phonePayload(session) });
      return;
    }

    /* ---- worker bring-up (contract §3, optional) ---- */
    if (path === '/v1/workers/register' && method === 'POST') {
      const body = await readBody(req, 4096).catch(() => '');
      let deviceId = 'phone_1';
      try {
        const parsed = JSON.parse(body) as { device_id?: string };
        if (parsed.device_id) deviceId = parsed.device_id.slice(0, 64);
      } catch {
        /* keep default */
      }
      const secure = req.headers['x-forwarded-proto'] === 'https';
      const wsBase = `${secure ? 'wss' : 'ws'}://${req.headers.host ?? 'localhost'}`;
      json(res, 200, {
        worker_id: deviceId,
        session_id: session.id,
        ws_url: `${wsBase}/v1/present-connect?session=${encodeURIComponent(session.id)}&role=phone&device=${encodeURIComponent(deviceId)}`,
      });
      return;
    }

    if (/^\/v1\/workers\/[^/]+\/health$/.test(path) && method === 'GET') {
      json(res, 200, {
        ok: session.phone.connected && !session.phone.stub,
        recording: session.phone.recording,
        processing: session.phone.processing,
      });
      return;
    }

    if (path === '/v1/health' && method === 'GET') {
      json(res, 200, {
        ok: true,
        session_id: session.id,
        phone_connected: session.phone.connected,
        phone_stub: session.phone.stub,
        demo_phase: session.demo.phase,
        slide_index: session.slideIndex,
        slides: SLIDES.length,
        screens: [...sockets].filter((s) => s.role === 'screen').length,
        rows: session.transcript.length,
      });
      return;
    }

    /* ---- presenter console ---- */
    if (path === '/api/login' && method === 'POST') {
      const key = req.socket.remoteAddress ?? 'unknown';
      if (throttled(key)) {
        json(res, 429, { error: 'too_many_attempts' });
        return;
      }
      const body = await readBody(req, 1024).catch(() => '');
      let token = '';
      try {
        token = (JSON.parse(body) as { token?: string }).token ?? '';
      } catch {
        token = '';
      }
      if (!config.adminToken || !token || !constantTimeEqual(token, config.adminToken)) {
        json(res, 401, { error: 'invalid_token' });
        return;
      }
      attempts.delete(key);
      const secure = req.headers['x-forwarded-proto'] === 'https' || (req.socket as { encrypted?: boolean }).encrypted === true;
      json(res, 200, { ok: true, session_id: session.id }, {
        'set-cookie': `presenter=${encodeURIComponent(config.adminToken)}; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800${secure ? '; Secure' : ''}`,
      });
      return;
    }

    if (path === '/api/logout' && method === 'POST') {
      json(res, 200, { ok: true }, { 'set-cookie': 'presenter=; Path=/; HttpOnly; Max-Age=0' });
      return;
    }

    if (path === '/api/command' && method === 'POST') {
      if (!isPresenter(req)) {
        json(res, 401, { error: 'unauthorized' });
        return;
      }
      const body = await readBody(req, 2048).catch(() => '');
      let parsed: unknown;
      try {
        parsed = JSON.parse(body);
      } catch {
        json(res, 400, { error: 'bad_json' });
        return;
      }
      const ok = applyPresenterCommand(parsed, () => {
        /* reply travels in the HTTP body */
      });
      json(res, ok ? 200 : 400, ok ? checkpointPayload(session) : { error: 'bad_command' });
      return;
    }

    if (path === '/api/state' && method === 'GET') {
      json(res, 200, {
        ...checkpointPayload(session),
        presenter: isPresenter(req),
        transcript: session.transcript.slice(-60),
      });
      return;
    }

    if (path === '/api/invite' && method === 'GET') {
      const secure = req.headers['x-forwarded-proto'] === 'https';
      const host = req.headers.host ?? 'localhost';
      json(res, 200, {
        join_url: `${secure ? 'https' : 'http'}://${host}/join?session=${encodeURIComponent(session.id)}`,
        session_id: session.id,
        slides: SLIDES,
      });
      return;
    }

    if (path.startsWith('/api/') || path.startsWith('/v1/')) {
      json(res, 404, { error: 'not_found' });
      return;
    }

    if (method !== 'GET' && method !== 'HEAD') {
      json(res, 405, { error: 'method_not_allowed' });
      return;
    }

    await serveStatic(req, res, path);
  } catch (error) {
    json(res, 500, { error: 'internal', detail: error instanceof Error ? error.message : 'unknown' });
  }
});

/* ------------------------------------------------------------------ *
 * WebSocket: /v1/present-connect?session&role&device
 * ------------------------------------------------------------------ */

const wss = new WebSocketServer({ server, path: '/v1/present-connect', maxPayload: 1024 * 1024 });

wss.on('connection', (ws: WebSocket, request: http.IncomingMessage) => {
  const url = new URL(request.url ?? '/', 'http://localhost');
  const rawRole = url.searchParams.get('role');
  // 'worker' is accepted as an alias so the Android flavour needs no change.
  const role: Role = rawRole === 'phone' || rawRole === 'worker' ? 'phone' : rawRole === 'presenter' ? 'presenter' : 'screen';

  if (role === 'presenter' && !isPresenter(request)) {
    send(ws, { type: 'error', code: 'UNAUTHORIZED', message: 'presenter cookie required' });
    ws.close(4001, 'unauthorized');
    return;
  }
  if (role === 'phone' && !isPhone(request)) {
    send(ws, { type: 'error', code: 'UNAUTHORIZED', message: 'phone token required' });
    ws.close(4001, 'unauthorized');
    return;
  }

  const sock: Sock = {
    id: Math.random().toString(36).slice(2, 10),
    ws,
    role,
    alive: true,
    lastSeenAt: Date.now(),
    deviceId: url.searchParams.get('device') ?? undefined,
  };
  sockets.add(sock);

  if (role === 'screen' || role === 'presenter') {
    send(ws, {
      type: 'snapshot',
      ...checkpointPayload(session),
      transcript: session.transcript.slice(-60),
      banner: session.demo.phase === 'fallback' ? RESERVE_BANNER : null,
      reserve_rows: RESERVE_ROWS,
    });
  }

  ws.on('message', (raw: RawData, isBinary: boolean) => {
    sock.alive = true;
    sock.lastSeenAt = Date.now();
    markPhoneSeenIfPhone(sock);

    if (isBinary) {
      // Single-phone demo carries no audio over the wire; ignore stray frames.
      return;
    }
    const text = toBuffer(raw).toString('utf8');

    if (role === 'phone') {
      handlePhoneMessage(sock, text);
      return;
    }
    if (role === 'presenter') {
      let parsed: unknown;
      try {
        parsed = JSON.parse(text);
      } catch {
        send(ws, { type: 'error', code: 'BAD_COMMAND', message: 'invalid json' });
        return;
      }
      applyPresenterCommand(parsed, (payload) => send(ws, payload));
      return;
    }
    // screens are read-only
  });

  ws.on('error', () => {
    /* surfaced via close */
  });

  ws.on('close', () => {
    sockets.delete(sock);
    if (role === 'phone' && phoneSock === sock) phoneLost('phone_disconnected');
  });
});

function markPhoneSeenIfPhone(sock: Sock): void {
  if (sock.role === 'phone') markPhoneSeen();
}

/* ------------------------------------------------------------------ *
 * Liveness: contract §2 — ping the phone every ~15 s
 * ------------------------------------------------------------------ */

const heartbeat = setInterval(() => {
  const now = Date.now();

  for (const sock of sockets) {
    if (sock.role === 'phone') {
      send(sock.ws, { type: 'ping', t: now });
      if (!sock.alive || now - sock.lastSeenAt > config.phoneStallMs) sock.ws.terminate();
      sock.alive = false;
      continue;
    }
    send(sock.ws, { type: 'ping', t: now });
    if (!sock.alive || now - sock.lastSeenAt > config.displayStallMs) sock.ws.terminate();
    sock.alive = false;
  }

  if (session.phone.connected && now - session.phone.lastSeenAt > config.phoneStallMs) {
    phoneSock?.ws.terminate();
    phoneLost('phone_stall');
  }
}, config.pingIntervalMs);
heartbeat.unref?.();

server.listen(config.port, config.host, () => {
  const shown = config.host === '0.0.0.0' ? 'localhost' : config.host;
  console.log(`[web] screens  http://${shown}:${config.port}/`);
  console.log(`[web] console  http://${shown}:${config.port}/presenter`);
  console.log(`[web] phone ws ws://${shown}:${config.port}/v1/present-connect?session=${session.id}&role=phone&device=phone_1`);
  console.log(`[web] phone http POST /v1/transcript  GET /v1/presentation`);
  console.log(`[web] slides   ${SLIDES.join(' → ')}`);
  if (!config.adminToken) console.warn('[web] ADMIN_TOKEN is empty — presenter console is disabled');
});

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    stopSim();
    for (const sock of sockets) sock.ws.close(1001, 'server shutting down');
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 1500).unref();
  });
}
