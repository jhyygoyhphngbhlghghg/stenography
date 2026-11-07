import { z } from 'zod';

/* ------------------------------------------------------------------ *
 * present-connect/1 — phone worker side (docs/PRESENT_CONNECT.md)
 *
 * The phone owns the microphone and every model (VAD, diarization, ASR,
 * the GGUF intonation post-pass). This server never receives audio in the
 * single-phone demo; it only receives finished rows and relays them to the
 * screens. That is the whole reason the demo can be claimed as on-device.
 * ------------------------------------------------------------------ */

export const WorkerHelloSchema = z.object({
  type: z.literal('hello'),
  protocol: z.string(),
  max_streams: z.number().int().min(1).max(64).optional(),
  sample_rate: z.number().int().optional(),
  encoding: z.string().optional(),
  models: z.array(z.string()).optional(),
  accepts_diarization: z.boolean().optional(),
});

/** The brief's four fields, exactly — docs/JSON.md. */
export const WireUtteranceSchema = z.object({
  speaker_id: z.string().min(1).max(40),
  start_ms: z.number().int().min(0),
  end_ms: z.number().int().min(0),
  text: z.string().max(4000),
});

export const WorkerTranscriptFinalSchema = z.object({
  type: z.literal('transcript.final'),
  stream_id: z.string().max(40).optional(),
  utterance: WireUtteranceSchema,
});

export const WorkerTranscriptPartialSchema = z.object({
  type: z.literal('transcript.partial'),
  stream_id: z.string().max(40).optional(),
  speaker_id: z.string().min(1).max(40),
  start_ms: z.number().int().min(0),
  end_ms: z.number().int().min(0),
  text: z.string().max(4000),
});

export const WorkerStatusSchema = z.object({
  type: z.literal('phone.status'),
  recording: z.boolean().optional(),
  processing: z.boolean().optional(),
  engine: z.string().max(80).nullable().optional(),
  note: z.string().max(200).nullable().optional(),
});

export const WorkerErrorSchema = z.object({
  type: z.literal('error'),
  stream_id: z.string().max(40).optional(),
  code: z.string().max(64),
  message: z.string().max(500),
});

export const WorkerPongSchema = z.object({
  type: z.literal('pong'),
  t: z.number().optional(),
});

export const WorkerMessageSchema = z.discriminatedUnion('type', [
  WorkerHelloSchema,
  WorkerTranscriptFinalSchema,
  WorkerTranscriptPartialSchema,
  WorkerStatusSchema,
  WorkerErrorSchema,
  WorkerPongSchema,
]);

/* ------------------------------------------------------------------ *
 * HTTP ingest — the simplest path for the phone to publish results.
 * ------------------------------------------------------------------ */

export const IngestTranscriptSchema = z.object({
  transcript: z.array(WireUtteranceSchema).min(1).max(2000),
  /** Replace the feed instead of appending (a fresh «Расшифровать» run). */
  reset: z.boolean().optional(),
  engine: z.string().max(80).optional(),
  session_id: z.string().max(64).optional(),
});

export const IngestStatusSchema = z.object({
  recording: z.boolean().optional(),
  processing: z.boolean().optional(),
  engine: z.string().max(80).nullable().optional(),
  note: z.string().max(200).nullable().optional(),
});

/* ------------------------------------------------------------------ *
 * Presenter console — checkpoint authoring
 * ------------------------------------------------------------------ */

export const PresenterCommandSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('checkpoint.set'), index: z.number().int().min(0), t_ms: z.number().int().min(0).optional() }),
  z.object({ type: z.literal('checkpoint.step'), delta: z.number().int().min(-20).max(20) }),
  z.object({ type: z.literal('checkpoint.goto'), slide: z.string() }),
  z.object({ type: z.literal('demo.begin') }),
  z.object({ type: z.literal('demo.end') }),
  z.object({ type: z.literal('demo.fallback') }),
  z.object({ type: z.literal('demo.simulate'), on: z.boolean() }),
  z.object({ type: z.literal('transcript.clear') }),
  z.object({ type: z.literal('session.rotate') }),
]);

/* ------------------------------------------------------------------ *
 * Types
 * ------------------------------------------------------------------ */

export type WorkerMessage = z.infer<typeof WorkerMessageSchema>;
export type WireUtterance = z.infer<typeof WireUtteranceSchema>;

/** A transcript row as the screens render it. `source` keeps provenance honest. */
export interface TranscriptRow {
  speaker_id: string;
  start_ms: number;
  end_ms: number;
  text: string;
  stream_id: string | null;
  seat: number;
  /** false while the line is still being formed */
  partial?: boolean;
  source: 'phone' | 'worker' | 'simulated' | 'replay';
  received_at: number;
}

export function safeParse<T>(schema: z.ZodType<T>, value: unknown): { ok: true; data: T } | { ok: false; error: string } {
  const result = schema.safeParse(value);
  return result.success
    ? { ok: true, data: result.data }
    : { ok: false, error: result.error.issues[0]?.message ?? 'invalid' };
}
