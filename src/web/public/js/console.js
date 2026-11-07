/* Presenter console: authors checkpoints, drives the demo, shows the room QR. */

const SLIDE_LABELS = {
  title: '01 Титул',
  problem: '02 Проблема',
  approach: '03 Подход',
  pipeline: '04 Конвейер',
  'on-device': '05 Приватность',
  live: '06 Живое демо',
  resilience: '07 Отказоуст.',
  numbers: '08 Цифры',
  team: '09 Команда',
};

const $ = (id) => document.getElementById(id);
const loginPanel = $('login');
const controls = $('controls');
const tokenInput = $('token');
const loginBtn = $('login-btn');
const hint = $('login-hint');
const slideGrid = $('slide-grid');
const cSlide = $('c-slide');
const cPhone = $('c-phone');
const cPhase = $('c-phase');
const cRows = $('c-rows');
const cFeed = $('c-feed');
const simToggle = $('sim-toggle');
const scrollToggle = $('scroll-toggle');
const qrCanvas = $('qr');
const joinUrlEl = $('join-url');

const state = {
  slides: [],
  index: 0,
  authed: false,
  ws: null,
  reconnectDelay: 800,
  pollTimer: null,
  joinUrl: '',
};

function setHint(text, bad = false) {
  hint.textContent = text ?? '';
  hint.dataset.bad = String(bad);
}

/* ------------------------------------------------------------------ *
 * Auth
 * ------------------------------------------------------------------ */

async function login() {
  const token = tokenInput.value.trim();
  if (!token) {
    setHint('Введите токен.', true);
    return;
  }
  loginBtn.disabled = true;
  try {
    const response = await fetch('/api/login', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ token }),
    });
    if (!response.ok) {
      setHint(response.status === 429 ? 'Слишком много попыток. Подождите.' : 'Токен не подошёл.', true);
      return;
    }
    state.authed = true;
    loginPanel.hidden = true;
    controls.hidden = false;
    setHint('');
    await refresh();
    await updateInvite();
    connect();
  } catch {
    setHint('Сервер недоступен.', true);
  } finally {
    loginBtn.disabled = false;
  }
}

loginBtn.addEventListener('click', () => void login());
tokenInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') void login(); });

/* ------------------------------------------------------------------ *
 * Commands
 * ------------------------------------------------------------------ */

function sendCommand(payload) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    state.ws.send(JSON.stringify(payload));
    return;
  }
  void fetch('/api/command', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify(payload),
  }).then((r) => { if (r.ok) return r.json(); throw new Error('bad'); })
    .then((snapshot) => render(snapshot))
    .catch(() => setHint('Команда не прошла: нет связи с сервером.', true));
}

async function refresh() {
  try {
    const response = await fetch('/api/state', { cache: 'no-store', credentials: 'same-origin' });
    if (response.status === 401) {
      state.authed = false;
      controls.hidden = true;
      loginPanel.hidden = false;
      setHint('Сессия истекла — войдите снова.', true);
      return;
    }
    if (response.ok) render(await response.json());
  } catch {
    /* polling continues */
  }
}

/* ------------------------------------------------------------------ *
 * Render
 * ------------------------------------------------------------------ */

function buildSlideButtons(slides) {
  slideGrid.innerHTML = '';
  slides.forEach((slideId, index) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = SLIDE_LABELS[slideId] ?? `${index + 1} ${slideId}`;
    button.dataset.index = String(index);
    button.addEventListener('click', () => sendCommand({ type: 'checkpoint.set', index, t_ms: 0 }));
    slideGrid.appendChild(button);
  });
}

function render(snapshot) {
  if (!snapshot) return;
  const slides = snapshot.slides ?? [];
  if (slides.length && slides.length !== state.slides.length) {
    state.slides = slides;
    buildSlideButtons(slides);
  }
  state.index = snapshot.slide_index ?? 0;

  Array.from(slideGrid.children).forEach((button, index) => {
    button.dataset.on = String(index === state.index);
  });
  cSlide.textContent = `${String(state.index + 1).padStart(2, '0')} / ${String(slides.length || 9).padStart(2, '0')}`;

  const phone = snapshot.phone ?? {};
  cPhone.textContent = phone.connected
    ? (phone.stub ? 'телефон: нет моделей' : 'телефон: на связи')
    : 'телефон: не подключён';
  cPhone.dataset.bad = String(!phone.connected || phone.stub);

  const phase = snapshot.demo_phase ?? 'idle';
  cPhase.textContent = `демо: ${phase === 'live' ? 'в эфире' : phase === 'fallback' ? 'резерв' : 'ожидание'}`;
  cPhase.dataset.bad = String(phase === 'fallback');

  simToggle.checked = Boolean(snapshot.simulate);
  // The server owns this flag: if another console tab flipped it, this one
  // follows instead of showing a box the audience's scroll no longer matches.
  if (snapshot.scroll_locked !== undefined) scrollToggle.checked = !snapshot.scroll_locked;

  if (Array.isArray(snapshot.transcript)) {
    cRows.textContent = `${snapshot.transcript.length} строк`;
    renderTranscriptOnly(snapshot.transcript);
  }

}

/* ------------------------------------------------------------------ *
 * QR + join link
 * ------------------------------------------------------------------ */

async function updateInvite() {
  try {
    const response = await fetch('/api/invite', { cache: 'no-store' });
    if (!response.ok) return;
    const data = await response.json();
    state.joinUrl = data.join_url;
    joinUrlEl.textContent = data.join_url;
    drawQr(data.join_url);
  } catch {
    /* keep previous */
  }
}

function drawQr(text) {
  if (!text || typeof window.qrcode !== 'function') return;
  const qr = window.qrcode(0, 'M');
  qr.addData(text);
  qr.make();
  const ctx = qrCanvas.getContext('2d');
  if (!ctx) return;
  const count = qr.getModuleCount();
  const cell = Math.floor(qrCanvas.width / (count + 2));
  const margin = Math.floor((qrCanvas.width - cell * count) / 2);
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, qrCanvas.width, qrCanvas.height);
  ctx.fillStyle = '#0b0e14';
  for (let r = 0; r < count; r += 1) {
    for (let c = 0; c < count; c += 1) {
      if (qr.isDark(r, c)) ctx.fillRect(margin + c * cell, margin + r * cell, cell, cell);
    }
  }
}

/* ------------------------------------------------------------------ *
 * WebSocket (presenter role) + polling fallback
 * ------------------------------------------------------------------ */

function connect() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  let ws;
  try {
    ws = new WebSocket(`${proto}//${location.host}/v1/present-connect?role=presenter`);
  } catch {
    startPolling();
    return;
  }
  state.ws = ws;

  ws.addEventListener('open', () => { state.reconnectDelay = 800; stopPolling(); });
  ws.addEventListener('message', (event) => {
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.type === 'snapshot' || message.type === 'checkpoint' || message.type === 'command.ok') {
      render(message);
    } else if (message.type === 'transcript.final' || message.type === 'transcript.partial') {
      refreshRowsOnly();
    } else if (message.type === 'transcript.clear' || message.type === 'transcript.replay') {
      refreshRowsOnly();
    } else if (message.type === 'phone' || message.type === 'demo') {
      refresh();
    } else if (message.type === 'ping') {
      try { ws.send(JSON.stringify({ type: 'pong', t: message.t })); } catch { /* closing */ }
    } else if (message.type === 'error') {
      setHint(message.message ?? 'ошибка', true);
    }
  });
  ws.addEventListener('close', () => {
    state.ws = null;
    startPolling();
    setTimeout(() => { if (state.authed && !state.ws) connect(); }, state.reconnectDelay);
    state.reconnectDelay = Math.min(8000, state.reconnectDelay * 1.7);
  });
  ws.addEventListener('error', () => { try { ws.close(); } catch { /* closed */ } });
}

function startPolling() {
  if (state.pollTimer) return;
  state.pollTimer = setInterval(() => { if (state.authed) void refresh(); }, 1500);
}
function stopPolling() {
  if (state.pollTimer) clearInterval(state.pollTimer);
  state.pollTimer = null;
}

let rowsRefreshQueued = false;
function refreshRowsOnly() {
  if (rowsRefreshQueued) return;
  rowsRefreshQueued = true;
  requestAnimationFrame(() => {
    rowsRefreshQueued = false;
    // Fetch only the transcript slice, not the whole state.
    fetch('/api/state', { cache: 'no-store', credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : null))
      .then((snapshot) => {
        if (!snapshot || !Array.isArray(snapshot.transcript)) return;
        renderTranscriptOnly(snapshot.transcript);
        cRows.textContent = `${snapshot.transcript.length} строк`;
      })
      .catch(() => { /* keep previous */ });
  });
}

function renderTranscriptOnly(transcript) {
  cFeed.replaceChildren();
  for (const row of transcript.slice(-24).reverse()) {
    const div = document.createElement('div');
    div.className = 'console-row';
    div.dataset.source = row.source ?? 'phone';
    const who = document.createElement('span');
    who.textContent = row.speaker_id;
    const what = document.createElement('span');
    what.textContent = row.text;
    div.append(who, what);
    cFeed.appendChild(div);
  }
}

/* ------------------------------------------------------------------ *
 * Buttons
 * ------------------------------------------------------------------ */

const COMMANDS = {
  prev: () => ({ type: 'checkpoint.step', delta: -1 }),
  next: () => ({ type: 'checkpoint.step', delta: 1 }),
  begin: () => ({ type: 'demo.begin' }),
  end: () => ({ type: 'demo.end' }),
  fallback: () => ({ type: 'demo.fallback' }),
  clear: () => ({ type: 'transcript.clear' }),
};

document.querySelectorAll('[data-cmd]').forEach((button) => {
  button.addEventListener('click', () => {
    const cmd = button.dataset.cmd;
    if (cmd === 'open-join') {
      if (state.joinUrl) window.open(state.joinUrl, '_blank', 'noopener');
      return;
    }
    const make = COMMANDS[cmd];
    if (make) sendCommand(make());
  });
});

simToggle.addEventListener('change', () => {
  sendCommand({ type: 'demo.simulate', on: simToggle.checked });
});

scrollToggle.addEventListener('change', () => {
  sendCommand({ type: 'screen.scroll', on: scrollToggle.checked });
});

/* Keyboard: arrows drive checkpoints, the way a clicker would. */
document.addEventListener('keydown', (event) => {
  if (!state.authed) return;
  if (event.target instanceof HTMLInputElement) return;
  if (event.key === 'ArrowRight' || event.key === 'PageDown') {
    event.preventDefault();
    sendCommand({ type: 'checkpoint.step', delta: 1 });
  } else if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
    event.preventDefault();
    sendCommand({ type: 'checkpoint.step', delta: -1 });
  }
});

/* ------------------------------------------------------------------ *
 * Boot
 * ------------------------------------------------------------------ */

void (async () => {
  try {
    const response = await fetch('/api/state', { cache: 'no-store', credentials: 'same-origin' });
    if (response.ok) {
      const snapshot = await response.json();
      if (snapshot.presenter) {
        state.authed = true;
        loginPanel.hidden = true;
        controls.hidden = false;
        render(snapshot);
        await updateInvite();
        connect();
        return;
      }
    }
  } catch {
    /* fall through to login */
  }
  setHint('');
  await updateInvite();
})();

// After session rotate the invite URL changes; refresh it.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && state.authed) void updateInvite();
});

/* Clean up on pagehide. */
window.addEventListener('pagehide', () => {
  stopPolling();
  if (state.ws) { try { state.ws.close(); } catch { /* already closed */ } }
}, { once: true });
