export const TEAM = [
  { name: "MIK Coders", role: "Team Lead · Fullstack" },
  { name: "Hex.Team", role: "Android · Pipeline" },
  { name: "Eval Squad", role: "Metrics · QA" },
  { name: "Web Crew", role: "Design · Frontend" },
];

export const TEAM_NAME = "MIK Coders";
export const CASE_TITLE = "Нейро-стенографист";
export const CASE_TEXT =
  "Шесть человек говорят по очереди. Один телефон записывает, разделяет спикеров и выдаёт структурированный диалог: кто, когда и что сказал. Всё считается на устройстве — в авиарежиме, без облака.";

export const TECH = [
  { k: "01", t: "VAD · Silero", d: "Находит речь и её границы в потоке" },
  { k: "02", t: "Диаризация · pyannote 3.0", d: "Разделяет голоса, потолок K≤6" },
  { k: "03", t: "ASR · Zipformer-ru int8", d: "Распознавание речи на устройстве" },
  { k: "04", t: "LLM · Qwen2-0.5B Q4_0", d: "Интонационная пунктуация, локально" },
];

export const SOLUTIONS = [
  {
    t: "Всё на устройстве",
    d: "Авиарежим — это и есть демо-режим. Ни облачного ASR, ни удалённых вычислений.",
    m: "0 облаков",
  },
  { t: "WER 0.6471", d: "После потолка K≤6: 44 ошибки из 68. Было 0.7794 до потолка.", m: "−17%" },
  {
    t: "RTF 0.1173",
    d: "4 659 мс обработки на 39 718 мс аудио. Реальное время с запасом.",
    m: "8.5×",
  },
];

// Fallback lines for the live-transcript box when the backend is unreachable.
export const FAKE_TRANSCRIPT = [
  "Добрый день, мы команда MIK Coders.",
  "Сейчас я покажу, как работает наше решение.",
  "Модель распознаёт речь в реальном времени.",
  "Давайте пригласим кого-нибудь на сцену.",
  "Обратите внимание на задержку — она минимальна.",
  "Теперь немного о технологии под капотом.",
  "Спасибо за внимание!",
];

// Checkpoints: id, label, duration in seconds (total ≈ 105s).
export const CHECKPOINTS = [
  { id: "qr", label: "QR", dur: 10 },
  { id: "team", label: "Команда", dur: 11 },
  { id: "case", label: "Кейс", dur: 11 },
  { id: "tester", label: "Тестер", dur: 9 },
  { id: "demo", label: "Демо", dur: 24 },
  { id: "tech", label: "Технология", dur: 16 },
  { id: "solutions", label: "Решения", dur: 16 },
  { id: "end", label: "Финал", dur: 7 },
] as const;

// Phrase from the live transcript that morphs into an audio track on the tech slide.
export const TRACK_PHRASE = "Модель распознаёт речь в реальном времени";
export const TRACK_TEXT =
  "Текст снова превращается в звук — модель разбирает аудиодорожку и восстанавливает из неё слова. Zipformer-ru int8 работает на телефоне, без облака.";
