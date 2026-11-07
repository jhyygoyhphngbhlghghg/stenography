import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { SLIDES, SLIDE_LABELS, config, slideIndexOf, type SlideId } from './config.js';
import type { TranscriptRow } from './protocol.js';

export type DemoPhase = 'idle' | 'live' | 'fallback';

/**
 * The presenter's phone is the only capture source: one microphone, on-device
 * VAD + diarization + ASR, then it pushes finished rows here. The web server
 * holds no audio at all, which is what keeps the single-phone demo honest.
 */
export interface PhoneSource {
  connected: boolean;
  deviceId: string | null;
  engine: string | null;
  /** App reported it is recording from the mic. */
  recording: boolean;
  /** App reported it is running ASR/diarization. */
  processing: boolean;
  stub: boolean;
  lastSeenAt: number;
  lastPushAt: number | null;
  rowsPushed: number;
  note: string | null;
}

export interface SessionState {
  id: string;
  createdAt: number;
  /** Checkpoint every screen must follow. */
  slideIndex: number;
  slideId: SlideId;
  tMs: number;
  followToken: string;
  /** Bumped on every presenter change so screens can detect drift. */
  checkpointRevision: number;

  demo: {
    phase: DemoPhase;
    reason: string;
    startedAt: number | null;
    /** Rehearsal aid: synthesise rows with no phone attached. Always flagged. */
    simulate: boolean;
  };

  phone: PhoneSource;
  transcript: TranscriptRow[];

  screen: {
    /**
     * While true, screens are pinned to the console's checkpoint and the
     * browser refuses wheel/touch. Released after the talk so the room can
     * browse what was shown — the checkpoint itself keeps following the
     * console either way, so nothing goes out of sync.
     */
    scrollLocked: boolean;
  };
}

function newSessionId(): string {
  return `sess_${randomBytes(6).toString('hex')}`;
}

function newFollowToken(): string {
  return randomBytes(12).toString('base64url');
}

export function speakerForSeat(seat: number): string {
  return `speaker_${seat}`;
}

export function streamIdForSeat(seat: number): string {
  // Oracle mapping from StreamLabeler.kt: s01..s20 <-> speaker_1..speaker_20.
  return `s${String(seat).padStart(2, '0')}`;
}

/** Seat number behind a `speaker_N` id, used to colour the on-stage circles. */
export function seatFromSpeakerId(speakerId: string): number {
  const m = /^speaker[_-]?(\d+)$/i.exec(speakerId.trim());
  const n = m?.[1] ? Number.parseInt(m[1], 10) : 0;
  // Only the six on-stage seats map to a circle position; anything else is 0.
  return n >= 1 && n <= 6 ? n : 0;
}

export function createSession(id = newSessionId()): SessionState {
  return {
    id,
    createdAt: Date.now(),
    slideIndex: 0,
    slideId: SLIDES[0] ?? 'qr',
    tMs: 0,
    followToken: newFollowToken(),
    checkpointRevision: 1,
    demo: { phase: 'idle', reason: 'not_started', startedAt: null, simulate: false },
    phone: {
      connected: false,
      deviceId: null,
      engine: null,
      recording: false,
      processing: false,
      stub: false,
      lastSeenAt: 0,
      lastPushAt: null,
      rowsPushed: 0,
      note: null,
    },
    transcript: [],
    screen: { scrollLocked: true },
  };
}

export function gotoSlide(session: SessionState, indexOrId: number | string, tMs?: number): SessionState {
  const index = slideIndexOf(indexOrId);
  if (index < 0) throw new Error(`unknown slide: ${String(indexOrId)}`);
  session.slideIndex = index;
  session.slideId = SLIDES[index] ?? 'qr';
  if (tMs !== undefined) session.tMs = tMs;
  session.followToken = newFollowToken();
  session.checkpointRevision += 1;
  return session;
}

export function stepSlide(session: SessionState, delta: number): SessionState {
  const next = Math.min(SLIDES.length - 1, Math.max(0, session.slideIndex + delta));
  return gotoSlide(session, next, 0);
}

export function rotateSession(session: SessionState): SessionState {
  session.id = newSessionId();
  session.transcript = [];
  session.phone.rowsPushed = 0;
  session.phone.lastPushAt = null;
  session.demo = { phase: 'idle', reason: 'session_rotated', startedAt: null, simulate: session.demo.simulate };
  session.checkpointRevision += 1;
  session.followToken = newFollowToken();
  // A rotated session is a fresh run: the audience scroll goes back to locked.
  session.screen.scrollLocked = true;
  return session;
}

/**
 * Release or restore the audience scroll.
 *
 * The revision is bumped on purpose: screens dedupe pushed checkpoints by it,
 * so a lock change delivered under the old revision would be dropped as a
 * repeat and the room would keep a frozen (or still free) scroll.
 */
export function setScrollLock(session: SessionState, locked: boolean): void {
  if (session.screen.scrollLocked === locked) return;
  session.screen.scrollLocked = locked;
  session.checkpointRevision += 1;
}

export function phonePayload(session: SessionState) {
  return {
    connected: session.phone.connected,
    device_id: session.phone.deviceId,
    engine: session.phone.engine,
    recording: session.phone.recording,
    processing: session.phone.processing,
    stub: session.phone.stub,
    note: session.phone.note,
    last_seen_at: session.phone.lastSeenAt,
    last_push_at: session.phone.lastPushAt,
    rows_pushed: session.phone.rowsPushed,
  };
}

export function checkpointPayload(session: SessionState) {
  return {
    session_id: session.id,
    slide_index: session.slideIndex,
    slide_id: session.slideId,
    /** Russian display name for the current slide (frontend screens). */
    slide_label: SLIDE_LABELS[session.slideId],
    t_ms: session.tMs,
    follow_token: session.followToken,
    revision: session.checkpointRevision,
    demo_phase: session.demo.phase,
    demo_reason: session.demo.reason,
    simulate: session.demo.simulate,
    /** Whether the audience may scroll. Carried with the checkpoint so a
        screen that joins late gets the same freedom as everyone else. */
    scroll_locked: session.screen.scrollLocked,
    phone: phonePayload(session),
    slides: SLIDES,
  };
}

/**
 * The React frontend's dialogue schema: four fields and nothing else. Screens
 * connecting with `role=screen` get these in the `snapshot` message, and the
 * same shape comes back from `/v1/frontend/checkpoint`.
 */
export interface DialogueRow {
  speaker_id: string;
  text: string;
  start_ms: number;
  end_ms: number;
}

/**
 * Last `limit` rows reduced to the dialogue schema. Unlike {@link sortBrief}
 * this keeps reserve and simulated rows: a screen that reconnects mid-fallback
 * must still see the dialogue on stage (the banner carries the provenance).
 */
export function dialogueRows(rows: TranscriptRow[], limit = 60): DialogueRow[] {
  return rows.slice(-limit).map((r) => ({
    speaker_id: r.speaker_id,
    text: r.text,
    start_ms: r.start_ms,
    end_ms: r.end_ms,
  }));
}

export function pushTranscript(session: SessionState, row: TranscriptRow): void {
  session.transcript.push(row);
  if (session.transcript.length > config.maxTranscriptRows) {
    session.transcript.splice(0, session.transcript.length - config.maxTranscriptRows);
  }
}

export function replaceTranscript(session: SessionState, rows: TranscriptRow[]): void {
  session.transcript = rows.slice(-config.maxTranscriptRows);
}

export function sortBrief(rows: TranscriptRow[]): Array<{ speaker_id: string; start_ms: number; end_ms: number; text: string }> {
  return rows
    .filter((r) => r.source === 'phone' || r.source === 'worker')
    .map((r) => ({ speaker_id: r.speaker_id, start_ms: r.start_ms, end_ms: r.end_ms, text: r.text }))
    .sort((a, b) => a.start_ms - b.start_ms || a.speaker_id.localeCompare(b.speaker_id));
}

/**
 * Constant-time comparison that does not leak length: both sides are hashed to
 * a fixed 32-byte digest before timingSafeEqual, so a wrong-length guess takes
 * the same time as a near-miss.
 */
export function constantTimeEqual(a: string, b: string): boolean {
  const ha = createHash('sha256').update(a).digest();
  const hb = createHash('sha256').update(b).digest();
  return timingSafeEqual(ha, hb);
}
