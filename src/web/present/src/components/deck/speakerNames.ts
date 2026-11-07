import { useSyncExternalStore } from "react";

/**
 * Имена спикеров поверх `speaker_id`.
 *
 * Семантика повторяет приложение (`ui/SpeakerNames.kt`): ключ — `speaker_id`,
 * пустое значение очищает имя, без имени показывается сам идентификатор.
 * Здесь хранилище — localStorage вместо SharedPreferences: показ может
 * продолжиться после перезагрузки страницы, и зал не должен терять подписи.
 */
const STORAGE_KEY = "mikcoders.speaker-names";

let names: Record<string, string> = {};
const listeners = new Set<() => void>();

function load(): Record<string, string> {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null) return {};
    const out: Record<string, string> = {};
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof value === "string" && value.trim()) out[key] = value.trim();
    }
    return out;
  } catch {
    // Приватный режим и заблокированный localStorage — не повод терять показ.
    return {};
  }
}

function persist(): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(names));
  } catch {
    /* имена останутся в памяти до перезагрузки */
  }
}

function emit(): void {
  for (const listener of listeners) listener();
}

if (typeof window !== "undefined") names = load();

export function displayName(speakerId: string): string {
  return names[speakerId]?.trim() || speakerId;
}

export function setName(speakerId: string, name: string): void {
  const trimmed = name.trim();
  if (!trimmed) delete names[speakerId];
  else names[speakerId] = trimmed.slice(0, 40);
  persist();
  emit();
}

export function allNames(): Record<string, string> {
  return { ...names };
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function snapshot(): Record<string, string> {
  return names;
}

/** Re-renders the caller whenever any speaker is renamed. */
export function useSpeakerNames(): Record<string, string> {
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}
