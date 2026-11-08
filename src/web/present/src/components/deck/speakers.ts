import { SPEAKER_COLORS } from "./data";

/**
 * One colour per speaker, stable for the whole talk: the same person keeps the
 * same bar colour across checkpoints, so the audience can follow a voice
 * rather than re-learn it every slide.
 */
export function speakerColor(speakerId: string): string {
  const digits = Number(speakerId.replace(/\D/g, "")) || 1;
  const length = SPEAKER_COLORS.length;
  const index = (((digits - 1) % length) + length) % length;
  return SPEAKER_COLORS[index] ?? "currentColor";
}

/** `speaker_3` reads as `03` on a projector; anything unexpected is shown as-is. */
export function speakerLabel(speakerId: string): string {
  const digits = speakerId.replace(/\D/g, "");
  return digits ? digits.padStart(2, "0") : speakerId;
}
