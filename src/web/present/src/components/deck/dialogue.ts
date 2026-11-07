import { z } from "zod";

// Provisional wire contract: change this adapter when the final server contract arrives.
export const dialogueSchema = z.object({
  speaker_id: z.union([z.string().min(1).max(128), z.number().int().nonnegative()]),
  text: z.string().min(1).max(12000),
  start_ms: z.number().int().nonnegative().optional(),
  end_ms: z.number().int().nonnegative().optional(),
});
export type DialogueMessage = z.infer<typeof dialogueSchema>;
export type DialogueEntry = DialogueMessage & { sequence: number };
export type Telemetry = { ramMb: number | null; cpuPercent: number | null };
export type InputSnapshot = { entries: DialogueEntry[]; telemetry: Telemetry };
const listeners = new Set<() => void>();
let snapshot: InputSnapshot = { entries: [], telemetry: { ramMb: null, cpuPercent: null } };
let sequence = 0;
export const voiceSignal = { level: 0, connected: false };

export function receiveDialogue(json: unknown) {
  let value = json;
  if (typeof json === "string") {
    try {
      value = JSON.parse(json);
    } catch {
      return false;
    }
  }
  const result = dialogueSchema.safeParse(value);
  if (!result.success) return false;
  snapshot = {
    ...snapshot,
    entries: [...snapshot.entries, { ...result.data, sequence: ++sequence }].slice(-60),
  };
  listeners.forEach((listener) => listener());
  return true;
}
const telemetrySchema = z.object({
  ramMb: z.number().finite().nonnegative().nullable(),
  cpuPercent: z.number().finite().min(0).max(100).nullable(),
});
export function receiveTelemetry(value: unknown) {
  const parsed = telemetrySchema.safeParse(value);
  if (!parsed.success) return false;
  snapshot = { ...snapshot, telemetry: parsed.data };
  listeners.forEach((listener) => listener());
  return true;
}
export function resetDialogue() {
  snapshot = { entries: [], telemetry: { ramMb: null, cpuPercent: null } };
  listeners.forEach((listener) => listener());
}
export const getInputSnapshot = () => snapshot;
export const subscribeInput = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};
