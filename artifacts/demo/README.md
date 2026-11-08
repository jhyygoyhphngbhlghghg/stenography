# Материалы демонстрации

Пункт 7 отчётного материала — видео работы приложения + демонстрационные
материалы экспертов. **Статус на момент сдачи: видеозаписи нет (PENDING)** —
она снимается командой на живом телефоне утром 2026-11-08 и публикуется
ассетом демо-релиза (файлы `*.mp4/*.wav` в этой папке gitignored). Это
осознанная дыра, а не забывчивость. Сценарий съёмки — `docs/DEMO.md`,
подробный чеклист-дубль — `docs/demo/README.md`.

## Чеклист B — демонстрационные материалы (слоты и статусы)

| # | Материал (требование экспертов) | Слот | Статус |
|---|---|---|---|
| B1 | **Видео непрерывной передачи «динамик → микрофон телефона»** одним дублем, без монтажных склеек внутри передачи | `artifacts/demo/demo.mp4` (gitignored → ассет [демо-релиза](https://github.com/jhyygoyhphngbhlghghg/stenography/releases), ссылка отсюда) | **PENDING** — команда записывает на живом телефоне |
| B2 | **Исходный файл** (что играл динамик) | `artifacts/demo/source-audio.wav` (кандидат: `artifacts/demo-stage6-handoff/stem_or_slice_speaker_1_3m.wav`) | **PENDING** |
| B3 | **Полученный файл** (экспорт приложения: `speaker_id`, `start_ms`, `end_ms`, `text`) | `artifacts/demo/received-transcript.json` | **PENDING** |
| B4 | **Лог отправителя** (телефон: `adb logcat` — WS-подключение, `hello.ok`, пуши строк, `accepted:N`) | `artifacts/demo/log-sender.txt` | **PENDING** |
| B5 | **Лог приёмника** (дек/сервер: `/v1/health` до и после, консоль дека) | `artifacts/demo/log-receiver.txt` | **PENDING** |
| B6 | **Скриншот сравнения контрольных сумм** (`sha256sum` исходного аудио и полученного JSON крупно в кадре) | `artifacts/demo/checksums.png` + `checksums.sha256` | **PENDING** |
| B7 | **Фото стенда с видимым расстоянием** между устройствами (рулетка/маркировка в кадре, целевые 3–6 м по сцене DEMO_STAGE6) | `artifacts/demo/stand-photo.jpg` | **PENDING** |

Что уже доказано без телефона: живой дек с 6 RU-репликами —
`artifacts/agent3/gif/deck-live-6spk.gif`; handshake приложения v2 против
боевого сервера — `artifacts/SMOKE_PRESENTCONNECT_V2.md` (PASS);
app→site/site→app sync <5 с и QR-вход 10/10 — `docs/TEST_RESULTS.md` §веб.

## Сценарий офлайн-ролика DEMO_STAGE6 (60–90 секунд)

1. Главный экран: видны строки `Офлайн: Zipformer-ru + Silero VAD + diar
   (models в APK)`, `JNI libsherpa-onnx-jni.so: loaded`,
   `LLM: llama.cpp GGUF qwen2-0_5b-instruct-q4_0.gguf` — это доказательство,
   что обрабатывает модель, а не заготовка.
2. Включить авиарежим, показать значок на том же кадре.
3. **Импорт** диалога (или **Запись** с 3–6 м, 6 говорящих по-русски,
   по очереди).
4. **Расшифровать** — шкала стадий (VAD → диаризация K≤6 → ASR по репликам →
   локальная LLM).
5. Список реплик: `speaker_N`, `start_ms`/`end_ms`, текст с запятыми.
6. **Сохранить JSON** → показать файл в памяти устройства.
7. Финальный кадр: сумма времени обработки на экране (для RTF).

Запись на телефоне: `adb screenrecord /sdcard/stage6.mp4` (или штатная
запись экрана), затем `adb pull`. Файл в git не кладём
(`artifacts/demo/*.mp4` в `.gitignore`) — выкладываем как release-ассет и
ссылаемся отсюда.

## Что уже лежит здесь

| материал | где |
|---|---|
| frozen demo-микс с нарезкой по местам (3.0 м работает … 6.0 м не работает) | `artifacts/eval/demo-stress/stage6/listen/` |
| gold-расшифровка той же сцены | `samples/demo-stress/stage6/ref/DEMO_STAGE6.json` |
| примеры JSON-экспорта пайплайна | `artifacts/eval/demo-stress/DEMO_STAGE6_impl_hardcap6.json`, `artifacts/test-results/example-transcript.json` |
| замеры, скриншоты таблиц | `docs/TEST_RESULTS.md`, `artifacts/test-results/results.csv` |

## Про PresentConnect

(не слайд защиты) Path B ≤20 отдельных потоков — опциональный сетевой режим;
в ролике его показывать не нужно. Контракт: `docs/PRESENT_CONNECT.md`.
