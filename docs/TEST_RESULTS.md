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
| Demo-stress 20-talker (host Linux) | see section below; **no invented numbers** |
| Import → stub list → JSON (no models) | pending on-device |
| Zipformer-ru on-device | pending models (`scripts/download-models.sh`) |
| Diarization ≥2 speakers | pending on-device |
| Airplane mode | pending on-device |
| PresentConnect ≤20 streams | unit cap test; WS needs web BE |

## Demo-stress (20 talkers, cocktail party) — host Linux only

Cloud agents / this VM have **no KVM and no ADB**. The stress path is **Python sherpa-onnx** (Zipformer-ru int8 + `OfflineSpeakerDiarization`), not the Android emulator.

Fixture (SYNTHETIC eSpeak NG Russian, not a recorded corpus): 20 talkers in one mix; main `speaker_1` target **+3 dB** vs the sum of 19 babble voices; stream offsets staggered by 90 ms (PresentConnect ≤20). Recipe: `samples/demo-stress/README.md`. Generator: `python3 scripts/generate_demo_stress_mix.py`.

```bash
python3 scripts/eval_harness.py --suite demo-stress --run-host
python3 scripts/verify-transcript-json.py artifacts/eval/demo-stress/hyp.json
```

Metrics are **DER** (NIST-style, `scripts/eval_metrics.py`; 250 ms collar and uncollared), **JER**, **speaker purity**, **WER on the main speaker only**, **RTF**. Not `pyannote.metrics`. Overnight `clip_01..03` rows are untouched.

**Measured numbers live only in** `artifacts/eval/demo-stress/metrics.csv` and `metrics.json` after a real run. If inference cannot start (models missing, pip wheel missing, OOM, download fail), those files keep `pending` / `blocked` plus the real reason. Do not copy toy unit-test WER from `scripts/test_eval_metrics.py` into the table.

### Host Linux run (this VM)

Stack: Python `sherpa-onnx==2.0.1`, Zipformer-ru int8 + pyannote segmentation-3.0 + 3D-Speaker embedding, `num_clusters=20`. No Android / no ADB / no KVM. Fixture: SYNTHETIC eSpeak NG Russian, measured SNR **3.00 dB**, 20 talkers, 90 ms stream stagger, 16.93 s mix.

| test_id | status | DER (collar 250 ms) | DER uncollared | JER | purity | WER main | RTF | speakers hyp/ref |
|---|---|---|---|---|---|---|---|---|
| mix_20talker | measured | 0.9500 | 0.9476 | 0.9503 | 0.9947 | 1.0000 | 0.1592 | 1 / 20 |
| mix_20talker_main_dry | measured | 0.0217 | 0.1029 | 0.0933 | 0.9067 | 1.0000 | 0.0591 | 1 / 1 |
| smoke | measured | n/a (collar ate the 1.12 s file) | 0.9196 | 0.9731 | 0.2692 | 1.0000 | 0.8661 | 2 / 20 |

Wall: mix 2696 ms / 16930 ms; dry-main 1000 ms; smoke 970 ms. Hyp JSON validates (`verify-transcript-json.py`). Mix hyp collapsed to **one** segment `31–16957 ms` text `в общем демонстрации комнате`. Dry-main Zipformer text was `с` — eSpeak is **not** in-domain for Zipformer-ru, so WER=1.0 is not only a cocktail-party effect. Diarization did not separate 20 overlapped synthetic voices (expected stress failure, measured).

Re-run: `python3 scripts/eval_harness.py --suite demo-stress --run-host` after `generate_demo_stress_mix.py` + `download-models.sh`.
