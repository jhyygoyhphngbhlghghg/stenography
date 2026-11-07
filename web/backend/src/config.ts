import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

function int(name: string, fallback: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === '') return fallback;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function str(name: string, fallback: string): string {
  const raw = process.env[name];
  return raw === undefined || raw.trim() === '' ? fallback : raw;
}

const here = dirname(fileURLToPath(import.meta.url));

export const config = {
  host: str('HOST', '0.0.0.0'),
  port: int('PORT', 8080),
  /** Static site root. dist/ -> ../public ; src/ under tsx -> ../../public */
  publicDir: str('PUBLIC_DIR', resolve(here, '../../public')),

  /** present-connect/1 constants — must match docs/PRESENT_CONNECT.md */
  protocol: 'present-connect/1',
  encoding: 'pcm_s16le',
  sampleRate: 16_000,
  maxStreams: int('MAX_STREAMS', 20),

  sessionTtlMs: int('SESSION_TTL_MS', 6 * 60 * 60 * 1000),

  /** Contract §2: ping the phone every ~15 s. */
  pingIntervalMs: int('PING_INTERVAL_MS', 15_000),
  /** A phone that stops answering pings is treated as gone. */
  phoneStallMs: int('PHONE_STALL_MS', 45_000),
  /** A screen that stops answering pings is dropped. */
  displayStallMs: int('DISPLAY_STALL_MS', 60_000),

  /** Presenter console secret. Empty disables the console. */
  adminToken: str('ADMIN_TOKEN', ''),
  /** Optional shared secret for the phone link (header/cookie). Not required by the app. */
  phoneToken: str('PHONE_TOKEN', ''),

  maxTranscriptRows: int('MAX_TRANSCRIPT_ROWS', 600),
  maxBodyBytes: int('MAX_BODY_BYTES', 2 * 1024 * 1024),
} as const;

export const SLIDES = [
  'title',
  'problem',
  'approach',
  'pipeline',
  'on-device',
  'live',
  'resilience',
  'numbers',
  'team',
] as const;

export type SlideId = (typeof SLIDES)[number];

export function isSlideId(value: unknown): value is SlideId {
  return typeof value === 'string' && (SLIDES as readonly string[]).includes(value);
}

export function slideIndexOf(value: unknown): number {
  if (typeof value === 'number' && Number.isInteger(value) && value >= 0 && value < SLIDES.length) return value;
  if (isSlideId(value)) return SLIDES.indexOf(value);
  return -1;
}
