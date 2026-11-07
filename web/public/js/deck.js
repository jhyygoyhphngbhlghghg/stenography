import { animate, createTimeline, onScroll, stagger } from '../vendor/anime.js';

/* ------------------------------------------------------------------ *
 * Config
 * ------------------------------------------------------------------ */

const params = new URLSearchParams(location.search);
const SESSION = params.get('session') ?? '';
const FREE_SCROLL = params.has('free');          // rehearsal / debugging
const FORCE_DISPLAY = params.get('display');      // 'full' | 'compact'
const WS_PATH = '/v1/present-connect';
const POLL_MS = 1200;                             // contract §5: 1–2 Hz

const scroller = document.getElementById('scroller');
const sections = Array.from(document.querySelectorAll('.checkpoint'));
const rail = document.getElementById('rail');
const counter = document.getElementById('counter');
const feed = document.getElementById('feed');
const banner = document.getElementById('banner');
const feedState = document.getElementById('feed-state');
const hudPhone = document.getElementById('hud-phone');
const hudDemo = document.getElementById('hud-demo');
const hudSession = document.getElementById('hud-session');
const hudDot = document.getElementById('hud-dot');

const SEAT_COLORS = [
  'var(--seat-1)', 'var(--seat-2)', 'var(--seat-3)',
  'var(--seat-4)', 'var(--seat-5)', 'var(--seat-6)',
];

const state = {
  index: 0,
  revision: 0,
  ws: null,
  pollTimer: null,
  polling: false,
  connected: false,
  rows: new Map(),
  seats: new Map(),
};

/* ------------------------------------------------------------------ *
 * Display mode: projector (full) vs phone (compact)
 * ------------------------------------------------------------------ */

function applyDisplay() {
  const narrow = window.innerWidth < 900 || window.innerHeight < 620;
  const mode = FORCE_DISPLAY === 'full' || FORCE_DISPLAY === 'compact'
    ? FORCE_DISPLAY
    : (narrow ? 'compact' : 'full');
  document.body.dataset.display = mode;
}
applyDisplay();
window.addEventListener('resize', () => { applyDisplay(); });

/* ------------------------------------------------------------------ *
 * Scroll lock — the deck scrolls itself, viewers cannot
 * ------------------------------------------------------------------ */

function blockScroll(event) {
  if (FREE_SCROLL) return;
  event.preventDefault();
}

for (const type of ['wheel', 'touchmove']) {
  scroller.addEventListener(type, blockScroll, { passive: false });
}
window.addEventListener('keydown', (event) => {
  if (FREE_SCROLL) return;
  const keys = ['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', 'Space', ' '];
  if (keys.includes(event.key)) event.preventDefault();
});
// Belt and braces: snap back if anything moved the scroll position.
let expectedTop = 0;
let programmatic = false;

/* ------------------------------------------------------------------ *
 * Programmatic scroll to a checkpoint
 * ------------------------------------------------------------------ */

function topOf(index) {
  const section = sections[index];
  return section ? section.offsetTop : 0;
}

let scrollAnim = null;

function scrollToIndex(index, instant = false) {
  const target = topOf(index);
  expectedTop = target;
  if (instant) {
    programmatic = true;
    scroller.scrollTop = target;
    programmatic = false;
    return;
  }
  if (scrollAnim) scrollAnim.pause();
  const from = scroller.scrollTop;
  const proxy = { y: from };
  scrollAnim = animate(proxy, {
    y: target,
    duration: Math.min(1400, 520 + Math.abs(index - state.index) * 120),
    ease: 'inOut(3)',
    onUpdate: () => {
      programmatic = true;
      scroller.scrollTop = proxy.y;
      expectedTop = proxy.y;
      programmatic = false;
    },
  });
}

setInterval(() => {
  if (programmatic || FREE_SCROLL) return;
  if (Math.abs(scroller.scrollTop - expectedTop) > 2) {
    programmatic = true;
    scroller.scrollTop = expectedTop;
    programmatic = false;
  }
}, 400);

/* ------------------------------------------------------------------ *
 * Rail + counter
 * ------------------------------------------------------------------ */

sections.forEach((_, i) => {
  const tick = document.createElement('span');
  rail.appendChild(tick);
});

function paintRail() {
  Array.from(rail.children).forEach((tick, i) => {
    tick.dataset.on = String(i === state.index);
    tick.dataset.past = String(i < state.index);
  });
  counter.textContent = `${String(state.index + 1).padStart(2, '0')} / ${String(sections.length).padStart(2, '0')}`;
}

/* ------------------------------------------------------------------ *
 * Seats: six speakers around the presenter's phone
 * ------------------------------------------------------------------ */

function buildSeats() {
  const host = document.getElementById('seats');
  if (!host) return;
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '0 0 300 300');
  const cx = 150;
  const cy = 150;
  const R = 104;

  const ring = document.createElementNS(NS, 'circle');
  ring.setAttribute('class', 'seat-ring');
  ring.setAttribute('cx', cx);
  ring.setAttribute('cy', cy);
  ring.setAttribute('r', R);
  ring.setAttribute('stroke-dasharray', '2 5');
  svg.appendChild(ring);

  for (let i = 0; i < 6; i += 1) {
    const angle = (i / 6) * Math.PI * 2 - Math.PI / 2;
    const x = cx + Math.cos(angle) * R;
    const y = cy + Math.sin(angle) * R;

    const spoke = document.createElementNS(NS, 'line');
    spoke.setAttribute('x1', cx); spoke.setAttribute('y1', cy);
    spoke.setAttribute('x2', x); spoke.setAttribute('y2', y);
    spoke.setAttribute('stroke', 'rgba(11,14,20,0.10)');
    spoke.setAttribute('stroke-width', '1');
    svg.appendChild(spoke);

    const halo = document.createElementNS(NS, 'circle');
    halo.setAttribute('cx', x); halo.setAttribute('cy', y);
    halo.setAttribute('r', 15);
    halo.setAttribute('fill', 'none');
    halo.setAttribute('stroke', SEAT_COLORS[i]);
    halo.setAttribute('stroke-width', '1');
    halo.setAttribute('opacity', '0');
    halo.dataset.halo = String(i + 1);
    svg.appendChild(halo);

    const dot = document.createElementNS(NS, 'circle');
    dot.setAttribute('class', 'seat-node');
    dot.setAttribute('cx', x); dot.setAttribute('cy', y);
    dot.setAttribute('r', 7);
    dot.setAttribute('fill', 'var(--paper-2)');
    dot.setAttribute('stroke', SEAT_COLORS[i]);
    dot.setAttribute('stroke-width', '1.4');
    dot.dataset.seat = String(i + 1);
    svg.appendChild(dot);

    const label = document.createElementNS(NS, 'text');
    label.setAttribute('class', 'seat-label');
    label.setAttribute('x', x + Math.cos(angle) * 26);
    label.setAttribute('y', y + Math.sin(angle) * 26 + 4);
    label.setAttribute('text-anchor', 'middle');
    label.textContent = `speaker_${i + 1}`;
    svg.appendChild(label);

    state.seats.set(i + 1, { dot, halo });
  }

  // The phone in the middle: one microphone, everything on device.
  const body = document.createElementNS(NS, 'rect');
  body.setAttribute('class', 'phone-core');
  body.setAttribute('x', cx - 15); body.setAttribute('y', cy - 26);
  body.setAttribute('width', 30); body.setAttribute('height', 52);
  body.setAttribute('rx', 5);
  svg.appendChild(body);

  const mic = document.createElementNS(NS, 'circle');
  mic.setAttribute('cx', cx); mic.setAttribute('cy', cy - 16);
  mic.setAttribute('r', 2.4);
  mic.setAttribute('fill', 'var(--cyan)');
  mic.dataset.mic = '1';
  svg.appendChild(mic);

  const cap = document.createElementNS(NS, 'text');
  cap.setAttribute('class', 'seat-label');
  cap.setAttribute('x', cx); cap.setAttribute('y', cy + 62);
  cap.setAttribute('text-anchor', 'middle');
  cap.textContent = 'телефон: 1 микрофон · 6 спикеров';
  svg.appendChild(cap);

  host.appendChild(svg);
  state.mic = mic;
}
buildSeats();

function highlightSeat(seat) {
  for (const [n, node] of state.seats) {
    const active = n === seat;
    node.halo.setAttribute('opacity', active ? '1' : '0');
    node.dot.setAttribute('fill', active ? SEAT_COLORS[n - 1] : 'var(--paper-2)');
  }
  if (active_seat_timeout) clearTimeout(active_seat_timeout);
  active_seat_timeout = setTimeout(() => {
    for (const [, node] of state.seats) {
      node.halo.setAttribute('opacity', '0');
      node.dot.setAttribute('fill', 'var(--paper-2)');
    }
  }, 2600);
}
let active_seat_timeout = null;

/* ------------------------------------------------------------------ *
 * Transcript feed
 * ------------------------------------------------------------------ */

function seatColor(seat) {
  return seat >= 1 && seat <= 6 ? SEAT_COLORS[seat - 1] : 'var(--ink-3)';
}

function renderRow(row) {
  const key = `${row.speaker_id}:${row.start_ms}`;
  let el = state.rows.get(key);
  if (!el) {
    el = document.createElement('article');
    el.className = 'row';
    el.innerHTML = '<div class="who"><i></i><span></span></div><div class="what"></div>';
    feed.appendChild(el);
    state.rows.set(key, el);
    animate(el, { opacity: [0, 1], translateY: [10, 0], duration: 380, ease: 'out(3)' });
  }
  const seat = row.seat || Number(/(\d+)/.exec(row.speaker_id ?? '')?.[1] ?? 0);
  el.dataset.partial = String(Boolean(row.partial));
  el.dataset.source = row.source ?? 'phone';
  el.querySelector('.who i').style.background = seatColor(seat);
  el.querySelector('.who span').textContent = row.speaker_id;
  el.querySelector('.what').textContent = row.text;

  while (feed.children.length > 7) {
    const oldest = feed.children[0];
    feed.removeChild(oldest);
    for (const [k, node] of state.rows) if (node === oldest) state.rows.delete(k);
  }
  return { el, seat };
}

function onTranscriptRow(row) {
  const { seat } = renderRow(row);
  if (!row.partial && seat) highlightSeat(seat);
  if (feedState) {
    feedState.textContent = row.source === 'replay'
      ? 'запись'
      : row.source === 'simulated' ? 'симулятор' : 'телефон';
  }
}

function clearFeed() {
  feed.innerHTML = '';
  state.rows.clear();
  if (feedState) feedState.textContent = 'ожидание';
}

function setBanner(text) {
  if (!banner) return;
  const on = Boolean(text);
  banner.dataset.on = String(on);
  banner.textContent = text ?? '';
}

/* ------------------------------------------------------------------ *
 * HUD
 * ------------------------------------------------------------------ */

function paintHud(snapshot) {
  const phone = snapshot.phone ?? {};
  if (hudPhone) {
    const label = phone.connected
      ? (phone.stub ? 'телефон: нет моделей' : phone.recording ? 'телефон: запись' : phone.processing ? 'телефон: обработка' : 'телефон: на связи')
      : 'телефон: не подключён';
    hudPhone.textContent = label;
    hudPhone.dataset.bad = String(!phone.connected || phone.stub);
  }
  if (hudDemo) {
    const phase = snapshot.demo_phase ?? 'idle';
    hudDemo.textContent = `демо: ${phase === 'live' ? 'в эфире' : phase === 'fallback' ? 'резерв' : 'ожидание'}`;
    hudDemo.dataset.bad = String(phase === 'fallback');
  }
  if (hudSession) {
    hudSession.textContent = `сессия: ${snapshot.session_id ? snapshot.session_id.slice(5, 11) : '—'}`;
  }
  if (hudDot) {
    hudDot.dataset.live = String((snapshot.demo_phase ?? 'idle') === 'live' && Boolean(phone.connected));
    hudDot.dataset.warn = String((snapshot.demo_phase ?? 'idle') === 'fallback');
  }
  if (state.mic) {
    state.mic.setAttribute('fill', phone.recording ? 'var(--magenta)' : 'var(--cyan)');
  }
}

/* ------------------------------------------------------------------ *
 * Checkpoints: scroll-linked animations per panel
 * ------------------------------------------------------------------ */

function buildTimelines() {
  for (const section of sections) {
    const tl = createTimeline({
      defaults: { ease: 'out(3)', composition: 'replace' },
      autoplay: onScroll({
        container: '#scroller',
        target: section,
        enter: 'top bottom',
        leave: 'bottom top',
        sync: true,
        repeat: true,
      }),
    });

    const sweep = section.querySelector('.sweep');
    if (sweep) {
      tl.add(sweep, { top: ['0%', '100%'], duration: 1000, ease: 'linear' }, 0);
    }

    for (const el of section.querySelectorAll('[data-anim]')) {
      const kind = el.dataset.anim;
      if (kind === 'fade') {
        tl.add(el, { opacity: [0, 1], translateY: [16, 0], duration: 520 }, 40);
      } else if (kind === 'title') {
        tl.add(el, {
          opacity: [0, 1],
          translateY: [26, 0],
          clipPath: ['inset(0 0 100% 0)', 'inset(0 0 0% 0)'],
          duration: 900,
          ease: 'inOut(3)',
        }, 40);
      } else if (kind === 'list') {
        const items = el.matches('li') ? [el] : Array.from(el.children);
        tl.add(items, {
          opacity: [0, 1],
          translateX: [-14, 0],
          duration: 520,
          delay: stagger(70),
        }, 160);
      } else if (kind === 'panel') {
        const panels = el.classList.contains('panel') ? [el] : Array.from(el.querySelectorAll('.panel'));
        tl.add(panels.length ? panels : el, {
          opacity: [0, 1],
          translateY: [22, 0],
          duration: 700,
          delay: stagger(90),
        }, 180);
      } else if (kind === 'pipe') {
        tl.add(Array.from(el.children), {
          opacity: [0, 1],
          translateX: [-26, 0],
          duration: 560,
          delay: stagger(110),
        }, 140);
      } else if (kind === 'metrics') {
        const cards = Array.from(el.querySelectorAll('.metric'));
        tl.add(cards, {
          opacity: [0, 1],
          translateY: [18, 0],
          duration: 620,
          delay: stagger(90),
        }, 140);
        // Count-up driven by the same scroll progress.
        for (const card of cards) {
          const valueEl = card.querySelector('.v');
          if (!valueEl) continue;
          const target = Number(valueEl.dataset.count ?? 0);
          const dec = Number(valueEl.dataset.dec ?? 0);
          const proxy = { p: 0 };
          tl.add(proxy, {
            p: 1,
            duration: 900,
            ease: 'out(2)',
            onUpdate: () => {
              valueEl.textContent = (target * proxy.p).toFixed(dec);
            },
          }, 200);
        }
      }
    }
    section.__tl = tl;
  }
}

/* ------------------------------------------------------------------ *
 * Applying a checkpoint from the server
 * ------------------------------------------------------------------ */

function applyCheckpoint(snapshot, opts = {}) {
  paintHud(snapshot);

  if (snapshot.demo_phase === 'fallback') {
    setBanner(snapshot.banner ?? 'Резервный режим: показана запись, не результат модели');
  } else {
    setBanner(null);
  }

  const nextIndex = Math.max(0, Math.min(sections.length - 1, snapshot.slide_index ?? 0));
  const moved = nextIndex !== state.index;
  const first = opts.first === true;
  state.index = nextIndex;
  state.revision = snapshot.revision ?? state.revision;
  paintRail();
  scrollToIndex(nextIndex, first);
  return moved;
}

function applyTranscriptPayload(rows) {
  if (!Array.isArray(rows)) return;
  for (const row of rows) onTranscriptRow(row);
}

/* ------------------------------------------------------------------ *
 * Transport: WebSocket primary, HTTP checkpoint polling as fallback
 * ------------------------------------------------------------------ */

function startPolling() {
  if (state.polling) return;
  state.polling = true;
  const tick = async () => {
    try {
      const response = await fetch('/v1/presentation', { cache: 'no-store' });
      if (response.ok) {
        const snapshot = await response.json();
        applyCheckpoint(snapshot);
      }
    } catch {
      /* keep trying */
    }
  };
  void tick();
  state.pollTimer = setInterval(tick, POLL_MS);
}

function stopPolling() {
  state.polling = false;
  if (state.pollTimer) clearInterval(state.pollTimer);
  state.pollTimer = null;
}

function wsUrl() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const query = new URLSearchParams({ role: 'screen', session: SESSION || undefined });
  return `${proto}//${location.host}${WS_PATH}?${query.toString()}`;
}

let reconnectDelay = 800;

function connect() {
  let ws;
  try {
    ws = new WebSocket(wsUrl());
  } catch {
    startPolling();
    return;
  }
  state.ws = ws;

  ws.addEventListener('open', () => {
    state.connected = true;
    reconnectDelay = 800;
    stopPolling();
  });

  ws.addEventListener('message', (event) => {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }
    switch (message.type) {
      case 'snapshot':
        applyCheckpoint(message, { first: true });
        applyTranscriptPayload(message.transcript);
        if (message.banner) setBanner(message.banner);
        break;
      case 'checkpoint':
        applyCheckpoint(message);
        break;
      case 'phone':
        paintHud({ phone: message, demo_phase: state.demoPhase, session_id: state.sessionId });
        break;
      case 'demo':
        state.demoPhase = message.phase;
        paintHud({ phone: state.phoneSnapshot, demo_phase: message.phase, session_id: state.sessionId });
        if (message.banner) setBanner(message.banner);
        else if (message.phase !== 'fallback') setBanner(null);
        break;
      case 'transcript.final':
      case 'transcript.partial':
        if (message.row) onTranscriptRow(message.row);
        break;
      case 'transcript.replay':
        clearFeed();
        applyTranscriptPayload(message.rows);
        if (message.banner) setBanner(message.banner);
        break;
      case 'transcript.clear':
        clearFeed();
        break;
      case 'ping':
        try { ws.send(JSON.stringify({ type: 'pong', t: message.t })); } catch { /* closing */ }
        break;
      default:
        break;
    }
  });

  const onClose = () => {
    state.connected = false;
    startPolling();
    setTimeout(connect, reconnectDelay);
    reconnectDelay = Math.min(8000, reconnectDelay * 1.7);
  };
  ws.addEventListener('close', onClose);
  ws.addEventListener('error', () => { try { ws.close(); } catch { /* already closed */ } });
}

/* ------------------------------------------------------------------ *
 * Boot
 * ------------------------------------------------------------------ */

function boot() {
  paintRail();
  buildTimelines();
  scrollToIndex(0, true);
  // Prime the HUD so nothing reads "undefined" before the first checkpoint.
  paintHud({ phone: {}, demo_phase: 'idle', session_id: SESSION });
  // Seed state used by partial HUD updates.
  state.demoPhase = 'idle';
  state.phoneSnapshot = {};
  state.sessionId = SESSION;
  connect();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot, { once: true });
} else {
  boot();
}

/* Keep the initial panel visible even if the server never answers. */
setTimeout(() => {
  if (!state.connected && !state.polling) startPolling();
}, 2500);

