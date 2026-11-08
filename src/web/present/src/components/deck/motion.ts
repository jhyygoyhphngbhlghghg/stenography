import { CHECKPOINTS } from "./data";

const BASE = 1500;
const STEP = 260;
const LONG = 2400;

/**
 * Единый словарь движения. Кривые выбраны так, чтобы ускорение было резким в
 * начале и длинным в конце — всё «приезжает», ничего не «вспыхивает», — а
 * длительности согласованы между собой: переход сцены, появление букв,
 * линейка и лента говорят в одном темпе.
 */
export const motion = {
  /** Главный переход между чекпоинтами: чем дальше шаг, тем дольше едем. */
  sceneScroll: (distance: number) => ({
    duration: Math.min(LONG, BASE + Math.abs(distance) * STEP),
    ease: "inOutQuint",
  }),
  /**
   * Тот же переход, когда зал листает сам после доклада. Характер движения
   * сохраняется, темп — быстрее: человек ждёт сцену, а не ведущего.
   */
  audienceScroll: (distance: number) => ({
    duration: Math.min(1100, 640 + Math.abs(distance) * 170),
    ease: "inOutQuart",
  }),
  /** То, что ещё видно на экране, уходит мягко, а не обнуляется. */
  exit: { duration: 420, ease: "outQuad" },
  enter: { duration: LONG / 2 + 300, ease: "outExpo" },
  letters: { duration: 1200, ease: "outExpo" },
  rule: { duration: 1400, ease: "inOutQuart" },
  ui: { duration: 260, ease: "outCubic" },
  /** Позиция чекпоинта в шкале внизу: тот же темп, что и у скролла. */
  progressDuration: (index: number) => (CHECKPOINTS[index]?.dur ?? 10) * 1000,
} as const;
