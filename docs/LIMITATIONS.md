# Ограничения решения

## DEMO_STAGE6 — заявка презентации

**Презентация = DEMO_STAGE6. Не Path B. Не ≤20 потоков.**

- **6** спикеров, **RU**, на сцене.
- **Один** микрофон телефона. Каждый спикер **3–6 м** от микрофона.
- Реплики **sequential**. Только **лёгкое** перекрытие или перебивание в конце реплики.
- **Большая комната**, **средний** фон (babble/HVAC). **Не** студия.
- Микрофон телефона: band-limit, compression, затухание с расстоянием, reverb. **Не** чистый Common Voice.
- Один канал, far-field: VAD → диаризация → Zipformer-ru ASR (офлайн / on-device).

Цель johann: **ideal recognition** (ясные смены спикеров + пригодный текст). **WER/DER для DEMO_STAGE6 не выдуманы.** Eval владеет фикстурой и цифрами ([PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4)). **Scores pending Eval.** Студийную подмену не заявлять. Запрещены «should be fine», «robust», «handles noisy rooms», «graceful under echo».

Path A (cocktail ≥20 в один поток) — measured FAIL: **1/20**, DER **0.95**, main WER **1.00**, RTF **0.18**. Не демо.

## Поддерживаемые условия

- Android 8.0+ (minSdk 26), демо на современном устройстве.
- Вход: локальный файл (WAV/M4A/MP3 через SAF) или запись микрофоном приложения.
- Выход: JSON брифа + просмотр списка реплик.
- Офлайн-стенография: авиарежим после того, как модели лежат в filesDir/assets. Это контур **DEMO_STAGE6** (одна far-field запись).
- PresentConnect (**опциональный** Path B, не заявка презентации): до **20 отдельных** потоков (один mic = один `stream_id`). 21-й поток — `OVER_CAPACITY`. Под edge этого режима остаётся только stream tracking **20/20**. **WORST_CASE WER/DER — FAIL** (ниже).

## Optional PresentConnect (Path B) — not the presentation claim

Не DEMO_STAGE6. ≤20 отдельных потоков, PCM → VAD → ASR, `speaker_id` ← `stream_id`. Источник edge-цифр: Eval [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4) @ `9908353d`. Числа не выдуманы. Фикстуры **не** подкручивали после FAIL.

**Path B under edge: stream tracking still 20/20** (hyp/ref speakers) на каждой строке таблицы. Это единственная заявка **этого опционального режима**, которая переживает его edge. На слайды DEMO_STAGE6 её не ставить.

**WER/DER under WORST_CASE are FAIL.** Слайды: WORST_CASE WER≈0.91, DER≈1.30. Точные значения — строка таблицы: WER main **0.9130**, DER 250ms **1.2985**. Clean WER/DER на WORST_CASE и на EDGE_* **не** заявлять.

**Killers:** overlap/bleed, noise false alarms, bad mic. Reverb alone does **not** collapse Path B.

| test_id | DER 250ms | JER | WER main | RTF | hyp/ref |
|---|---|---|---|---|---|
| CLEAN Path B | 0.0913 | 0.1840 | 0.0870 | 0.1212 | 20/20 |
| EDGE_reverb | 0.0849 | 0.1756 | 0.1304 | 0.1263 | 20/20 |
| EDGE_overlap | 0.9936 | 0.5476 | 0.7391 | 0.2430 | 20/20 |
| EDGE_noise | 1.7005 | 0.5675 | 0.1739 | 0.2602 | 20/20 |
| EDGE_badmic | 0.6569 | 0.2863 | 1.0000 | 0.1910 | 20/20 |
| **WORST_CASE** | **1.2985** | **0.5981** | **0.9130** | **0.2907** | 20/20 |

- Overlap real: max 12 concurrent; 91% of speech frames ≥2 speakers.
- Main vs bleed: +3.0 dB (EDGE_overlap) / +1.5 dB (WORST_CASE).
- WORST_CASE main hyp collapses to one ~18s blob (`хочу говорить`). DER>1 = false-alarm time > ref speech.
- EDGE_reverb (DER 0.0849, WER main 0.1304, 20/20) — isolated-mic reverb alone does not collapse Path B. Это не разрешение цитировать clean WER/DER на EDGE_overlap, EDGE_noise, EDGE_badmic, WORST_CASE.
- Запрещены формулировки «should be fine», «robust», «handles noisy rooms», «graceful under echo». **Do not claim in slides** clean WER/DER на edge.

### Gate v2 hard `BAND_LIMITED` — REJECTED for bound seats

**STOP.** Hard `BAND_LIMITED` is **REJECTED** for bound seats (`s01`..`s20`). Source: Eval [PR #4](https://cursor.com/codebase/johann-schilling/neuro-stenographer/pull/4) gated_v2 @ `bed868b3` (gate pinned Implementer `e3ad908` / Platform `b12a2bb`). Rejection note @ `ffe53ba4` (`docs/TEST_RESULTS.md`, `docs/GATE.md` on that branch). Fixtures **not** retuned.

Measured consequence @ `bed868b3` (gated_v2 hyp — **not** the demo claim):

| test_id | gated_v2 hyp | gate |
|---|---|---|
| EDGE_noise | **19/20** | drop 1 `BAND_LIMITED` **s01** (main) |
| EDGE_badmic | **6/20** | drop 14 `BAND_LIMITED` |
| WORST_CASE | **0/20** | drop 20 `BAND_LIMITED`; empty `{transcript:[]}` |

**Do not claim** these gated_v2 hyp counts. **Do not claim** clean WER/DER on WORST_CASE. No new WER/DER from this gate.

Bound seats (`s01`..`s20`) must **soft-flag only** (still ASR), same spirit as `SOFT_LOW`. Never hard-drop main/`s01`. Hard `BAND_LIMITED` only for unbound/anonymous. Platform owns the code fix on PR #3. This doc locks the claim only.

**Для optional Path B** заявка под edge остаётся ungated stream tracking **20/20** (frozen baseline @ `9908353d`). Это не DEMO_STAGE6. Ungated WORST_CASE stays FAIL: WER main **0.9130**, DER 250ms **1.2985**. Hold merge. Overnight #2 first.

Ранний clean-замер того же PR #4 (до этого suite): Path B DER **0.24** (также 0.235), main WER **0.087**, RTF **2.65**, 20/20. Это не строка CLEAN этого suite (там DER 0.0913, RTF 0.1212, WER main 0.0870) и не WORST_CASE. Path A того раннего замера — hard fail: **1/20**, DER **0.95**, main WER **1.00**, RTF **0.18**. Офлайн-файл по-прежнему одна запись (VAD → диаризация → ASR) и не является 20-talker демо.

## Известные ограничения

- **Новые WER/RTF не выдумывать.** DEMO_STAGE6: **scores pending Eval**, цифр нет. Цитировать только замер H1 / PR #4 для Path A и optional Path B: ранний clean, overnight-клипы (~3.5% / 0% / 0% WER, без перекрытия), edge suite @ `9908353d` (таблица выше). Он-девайс строки в `artifacts/test-results/results.csv` пусты, пока нет своего прогона.
- Zipformer-ru — RU-first; английский best-effort (для EN — Whisper tiny, не default).
- DEMO_STAGE6 допускает только лёгкое перекрытие в конце реплики. Это не замер и не обещание качества. Path A на ≥20 говорящих в одном миксе — FAIL (1/20). На optional Path B killers — overlap/bleed (EDGE_overlap WER main 0.7391, DER 0.9936), noise false alarms (EDGE_noise DER 1.7005), bad mic (EDGE_badmic WER main 1.0000). WORST_CASE: WER main **0.9130**, DER **1.2985**, hyp/ref **20/20**. Clean WER/DER на эти строки не переносить. Не переносить их на DEMO_STAGE6.
- LLM post-pass: модуль есть, **веса не приложены** (TODO Gemma/Qwen/Phi via llama.cpp или MediaPipe). Сейчас identity / лёгкая пунктуация, честно в UI.
- Модели не в git (~74 MB+ ASR, плюс VAD/diar). Без `download-models.sh` / adb push приложение соберётся, но pipeline уйдёт в stub.
- NNAPI на OEM плавает; default provider = CPU.
- Длинное аудио без VAD-chunking деградирует (особенно если позже включим GigaAM). VAD обязателен.
- PresentConnect не заменяет офлайн-проверку судей. Судьи ставят APK, импорт трёх клипов, экспорт JSON.

## Что не входит в текущую версию

- Web FE презентации и checkpoint-polling BE (другой трек).
- Обучение/дообучение ASR.
- pyannote Python на телефоне.
- Word-level timestamps, merge/rename спикеров в UI (stretch).
- whisper.cpp, Picovoice, облачный fallback.

## Риски демонстрации

- Забыть скачать модели до авиарежима → stub на защите (дисквалификация по смыслу «подмена расшифровкой»). Чеклист: `docs/INSTALL.md`.
- OOM на mid-range, если подложить GigaAM (~216 MB) вместо Zipformer-ru.
- Gated pyannote segmentation: нужна атрибуция / принятие terms до редистрибуции.

## Дальнейшее развитие

См. kickoff: T1 ASR → T2 diar+align → T3 export/polish → freeze 2026-11-07 21:00 MSK.
