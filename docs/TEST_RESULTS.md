# Методика и результаты тестирования

Собственные прогоны команды (не судейский набор «котики»).

**Запрещено выдумывать WER/RTF.** Пока нет замера на реальном клипе — в таблице `pending`.

Шаблон строк: `artifacts/test-results/results.csv`.

## Методика (черновик)

1. Собрать debug APK flavor `stenographer`.
2. Положить модели (`scripts/download-models.sh` + push).
3. Авиарежим.
4. Импорт 2–3 своих диалогов (RU, ≥2 спикера, без перекрытия).
5. Экспорт JSON, сверка `speaker_id` / `start_ms` / `end_ms` / `text` с ручной расшифровкой.
6. Зафиксировать wall time и длительность аудио (RTF = wall / duration) только после реального замера.

## Статус overnight

| Проверка | Статус |
|---|---|
| Gradle `assembleDebug` | green on this machine (sherpa-onnx 1.13.5 AAR + Kotlin API) |
| Unit tests (`testStenographerDebugUnitTest`) | run via `scripts/ci-check.sh` |
| JSON schema (`verify-transcript-json.py`) | fixture OK; extra keys rejected |
| Eval WER+RTF (clip_01..03) | skeleton only — **no invented numbers**; CSV pending |
| Import → stub list → JSON (no models) | pending on-device |
| Zipformer-ru on-device | pending models (`scripts/download-models.sh`) |
| Diarization ≥2 speakers | pending on-device |
| Airplane mode | pending on-device |
| PresentConnect ≤20 streams | unit cap test; WS needs web BE — **not** the demo claim |

## DEMO_STAGE6 (cite only — not re-run on this PR)

Eval owns the wavs. Do not invent WER/DER.

| Source | What |
|---|---|
| `a8b39d5a` | agg WER **0.73**; 3.0 m **0**; 3.6 m **0.55**; 4.2–6.0 m **≈1.0**; **s5 missing** (5/6 hyp speakers) |
| `e0e64b85` | whole-file RMS AGC: agg WER **0.73→0.70**; **s5 still missing** |
| `54177a1e` | per-turn FarFieldGain: still **0.73**, s5 still missing — **gain exhausted** |
| `0824df64` | Hex keep-punct WER **0.7321→0.7794**; s1 **0.00→0.2857** (four punct deletions). Zipformer emits **NONE** |

This branch: frozen FarFieldGain; diar hole-fill/orphan for s5 (protect 3 m); llama.cpp GGUF ADD intonation punct. **Does not re-pack assets** (Platform PR #3 @ `af71d91`). Path B gates untouched.

```bash
python3 scripts/measure-farfield-gain.py   # synthetics for the stage() primitive; not the demo fix
cd src/android && ./gradlew :app:testStenographerDebugUnitTest :app:assembleDebug
```

Eval wavs / gold / harness **untouched**. New STAGE6 numbers pending Impl second-pass Eval.
