/**
 * Fake phone worker for rehearsing the stage without the device.
 *
 * It speaks present-connect/1 exactly like the Android flavour does: hello →
 * hello.ok, answers ping with pong, and pushes transcript.final rows in the
 * brief's four-field shape. Rows are tagged on the server side by their origin,
 * so this never pollutes the real transcript export unless the phone itself is
 * the source.
 *
 *   npm run sim:phone -- --url ws://localhost:8080/v1/present-connect
 *
 * A host that sets PHONE_TOKEN rejects the phone role without it, so the token
 * is sent as a bearer header. Read from --token, else from the environment,
 * which is what systemd's EnvironmentFile and the docs both use.
 *
 *   PHONE_TOKEN=… npm run sim:phone -- --url wss://present.example.org/v1/present-connect
 */
import WebSocket from 'ws';

const args = process.argv.slice(2);
function flag(name: string, fallback: string): string {
  const i = args.indexOf(name);
  const value = i >= 0 ? args[i + 1] : undefined;
  return value !== undefined && value !== '' ? value : fallback;
}

const BASE = flag('--url', 'ws://localhost:8080/v1/present-connect');
const DEVICE = flag('--device', 'phone_1');
const EVERY_MS = Number(flag('--every', '4000'));
const MODELS = flag('--models', 'zipformer-ru-int8');
const TOKEN = flag('--token', process.env.PHONE_TOKEN ?? '');

const LINES: Array<{ seat: number; text: string }> = [
  { seat: 1, text: 'Добрый день, начинаем защиту проекта.' },
  { seat: 2, text: 'Меня слышно со второго места, телефон в центре круга.' },
  { seat: 3, text: 'Главный вопрос: как вы держите приватность без облака?' },
  { seat: 4, text: 'Все модели лежат внутри APK и работают в авиарежиме.' },
  { seat: 5, text: 'А что происходит на дальних местах, в четырёх метрах?' },
  { seat: 6, text: 'Там включается гейт по энергии, и мы честно показываем флаг.' },
];

const url = new URL(BASE);
url.searchParams.set('role', 'phone');
url.searchParams.set('device', DEVICE);

const ws = new WebSocket(url.toString(), TOKEN ? { headers: { authorization: `Bearer ${TOKEN}` } } : undefined);
let index = 0;
let timer: NodeJS.Timeout | null = null;
let sessionClock = Date.now();

function nextRow() {
  const line = LINES[index % LINES.length] ?? { seat: 1, text: 'Проверяю связь.' };
  index += 1;
  const now = Date.now();
  const start = now - sessionClock;
  return {
    type: 'transcript.final',
    stream_id: `s${String(line.seat).padStart(2, '0')}`,
    utterance: {
      speaker_id: `speaker_${line.seat}`,
      start_ms: Math.max(0, start),
      end_ms: Math.max(0, start + 2400),
      text: line.text,
    },
  };
}

ws.on('open', () => {
  console.log(`[sim] connected -> ${url.toString()}`);
  ws.send(JSON.stringify({
    type: 'hello',
    protocol: 'present-connect/1',
    max_streams: 20,
    sample_rate: 16_000,
    encoding: 'pcm_s16le',
    models: MODELS === 'none' ? [] : [MODELS],
    accepts_diarization: true,
  }));
});

ws.on('message', (raw: WebSocket.RawData) => {
  const text = Buffer.isBuffer(raw) ? raw.toString('utf8') : String(raw);
  let message: { type?: string; t?: number; session_id?: string };
  try {
    message = JSON.parse(text);
  } catch {
    return;
  }

  if (message.type === 'hello.ok') {
    console.log(`[sim] hello.ok session=${message.session_id}`);
    sessionClock = Date.now();
    ws.send(JSON.stringify({ type: 'phone.status', recording: true, processing: false, engine: MODELS === 'none' ? null : MODELS }));
    timer = setInterval(() => {
      if (ws.readyState !== WebSocket.OPEN) return;
      const row = nextRow();
      ws.send(JSON.stringify(row));
      console.log(`[sim] ${row.utterance.speaker_id}: ${row.utterance.text}`);
    }, EVERY_MS);
    timer.unref?.();
  }

  if (message.type === 'ping') {
    ws.send(JSON.stringify({ type: 'pong', t: message.t ?? Date.now() }));
  }
});

ws.on('close', (code: number, reason: Buffer) => {
  console.log(`[sim] closed code=${code} reason=${reason.toString('utf8') || '—'}`);
  if (timer) clearInterval(timer);
  process.exit(0);
});

ws.on('error', (error: Error) => {
  console.error(`[sim] error: ${error.message}`);
  if (timer) clearInterval(timer);
});

process.on('SIGINT', () => {
  if (timer) clearInterval(timer);
  ws.close(1000, 'sim done');
  process.exit(0);
});
