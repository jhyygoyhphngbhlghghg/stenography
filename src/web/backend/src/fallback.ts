import type { TranscriptRow } from './protocol.js';
import { speakerForSeat, streamIdForSeat } from './store.js';

/**
 * Reserve transcript for the screens when the phone cannot publish results.
 *
 * This is never model output and is never presented as such: every row carries
 * `source: 'replay'`, the projector renders a persistent banner, and the
 * checkpoint reports `demo_phase: 'fallback'`. It exists so the stage keeps its
 * working layout — six seats, speaker colours, turn timing — while the presenter
 * restarts the app, instead of going to a blank screen on stage.
 *
 * Timing mirrors DEMO_STAGE6's shape (docs/PRESENT_CONNECT.md, README): six
 * Russian speakers, one microphone, sequential turns with light end-overlap,
 * roughly 40 s of dialogue.
 */
const RESERVE: Array<{ seat: number; start: number; end: number; text: string }> = [
  { seat: 1, start: 800, end: 6400, text: 'Итак, мы включили запись: все шесть человек видны на экране.' },
  { seat: 2, start: 6400, end: 11200, text: 'Да, я говорю со второго места, телефон лежит в центре круга.' },
  { seat: 3, start: 11400, end: 18600, text: 'Главный вопрос: что происходит с качеством, если человек стоит в четырёх метрах?' },
  { seat: 4, start: 18600, end: 25800, text: 'На дальних местах включается гейт по энергии, и модель честно помечает тихий сигнал.' },
  { seat: 5, start: 25900, end: 32400, text: 'А если телефон вообще потерять из сети во время выступления?' },
  { seat: 6, start: 32500, end: 39800, text: 'Тогда сайт переводит экраны в резервный режим и показывает это явно.' },
];

export const RESERVE_ROWS = RESERVE.length;

export const RESERVE_BANNER = 'Резервный режим: телефон не публикует расшифровку — показана запись';

export function reserveTranscript(): TranscriptRow[] {
  const base = Date.now();
  return RESERVE.map((row) => ({
    stream_id: streamIdForSeat(row.seat),
    seat: row.seat,
    speaker_id: speakerForSeat(row.seat),
    start_ms: base + row.start,
    end_ms: base + row.end,
    text: row.text,
    source: 'replay' as const,
    received_at: base,
  }));
}

/**
 * Rows the built-in simulator emits during rehearsal when no phone is attached.
 * Tagged `source: 'simulated'` so they can never be mistaken for a model result,
 * and excluded from the brief JSON export.
 */
const SIM_LINES: Array<{ seat: number; text: string }> = [
  { seat: 1, text: 'Проверяю первое место: меня должно быть видно.' },
  { seat: 2, text: 'Второе место на связи, звук идёт.' },
  { seat: 3, text: 'Третье место слышно хорошо.' },
  { seat: 4, text: 'Четвёртое место подключилось.' },
  { seat: 5, text: 'Пятое место в круге.' },
  { seat: 6, text: 'Шестое место готово.' },
];

export function simulatedLine(index: number): { seat: number; text: string } {
  const line = SIM_LINES[index % SIM_LINES.length];
  return line ?? { seat: 1, text: 'Проверяю связь.' };
}
