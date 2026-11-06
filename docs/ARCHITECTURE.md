# Архитектура решения

## Контекст

Hex.Team case «Нейро-стенографист»: Android-приложение локально превращает аудиозапись разговора в структурированную расшифровку (кто сказал, когда, что). Облачный ASR запрещён. Демо офлайн-пути — в авиарежиме.

**Live demo claim = DEMO_STAGE6:** 6 on-stage RU speakers, **one phone mic at 3–6 m** (Nothing-class), sequential turns with light end-overlap, large room + medium noise. Goal: ideal ASR + diarization on that single far-field channel.

PresentConnect Path B (≤20 browser streams) stays as a **secondary codepath** (flavor `presentConnect` / PR #6). It is **not** the slide claim. Do not retune Path B gates here.

Команда: **MIK Coders**. Этот репозиторий — Android-«backend» (обработка на телефоне) + контракт API для web FE/BE.

## Компоненты

```
Audio (SAF import / in-app 16 kHz mic)
        │
        ▼
  decode → 16 kHz mono PCM
        │
        ▼
  hard skip CLIPPED / BAD_MIC / empty n==0 only
  (energy / band-limit on bound main = soft, never drop 4–6 m)
        │
        ▼
  soft energy-VAD (20 ms, fire 0.0035 FS; keep speech-like)
        │
        ▼
  per-turn FarFieldGain.stage    # energy windows; 0.08 FS / +24 dB / peak 0.95
  (loud near turns ≤0 dB; NEVER whole-file AGC)
        │
        ▼
  OfflineSpeakerDiarization      # FastClustering numClusters=6
  + VAD↔diar reconcile (orphan / energy-hole / split; protect 3 m)
        │
        ▼
  Zipformer-ru int8 per (speaker, t0, t1)  # gain/EQ frozen
        │
        ▼
  llama.cpp GGUF (Qwen2-0.5B) ADD intonation punct
  → brief JSON {transcript:[{speaker_id,start_ms,end_ms,text}]}
```

Whole-file RMS AGC is **not** the demo fix. Eval **e0e64b85** (cite only): agg WER 0.73→0.70, **s5 still missing** — near seat already WER 0 @ 3.0 m (`a8b39d5a`). Per-turn gain lifts quiet 4–6 m seats without attenuating a hot 3 m peak.

PresentConnect (flavor `presentConnect`, network mode) — **codepath only**:

```
Browser mics (≤20) ──audio──► presentation BE ──WS/HTTP──► phone worker
                                                              │
                                                              ▼
                                                         same pipeline
                                                              │
                                                              ▼
                         presentation display ◄──transcript───┘
```

Checkpoint polling (clients follow presenter) lives on the **web BE**, not in this app. See `docs/PRESENT_CONNECT.md`.

## Основные технические решения

| Слой | Решение | Зачем |
|---|---|---|
| UI | Kotlin + Jetpack Compose, minSdk 28 | Brief 26–28; sherpa Java demo / AAR path uses 28 |
| Flavors | `stenographer` / `presentConnect` | Один код, флаг `BuildConfig.PRESENT_CONNECT` |
| Runtime | sherpa-onnx (JitPack AAR / JNI) | ASR + VAD + diarization в одном runtime |
| ASR primary | Zipformer-ru int8 (~74 MB listing) | RU-first, мобильный размер |
| ASR backup | Whisper tiny multilingual (sherpa) | RU+EN; не whisper.cpp |
| ASR emergency | Vosk small-ru | Если sherpa не встаёт на устройстве |
| VAD | Soft energy-VAD + Silero | High-recall 4–6 m; HVAC not a turn; no distant hard-drop |
| Gain | Per-turn `FarFieldGain.stage` (Platform @ db3afe44) | Quiet distant seats; do not crush 3 m |
| Diarization | sherpa `OfflineSpeakerDiarization` K=6 + hole-fill | Recover quiet far seats (s5 missing @ a8b39d5a); protect 3 m |
| LLM | llama.cpp GGUF Qwen2-0.5B Q4_0 | ADD spoken-intonation `, . ? !` (Zipformer emits NONE; cite 0824df64). Identity stub fails TZ. |
| I/O | MediaExtractor/MediaCodec → PCM; SAF import; AudioRecord | M4A/MP3/WAV + запись в приложении |
| Export | JSON schema брифа; TXT как бонус | `docs/JSON.md` |
| Модели | Не в git; Platform pack PR #3 @ af71d91 embeds ASR/VAD/diar | This PR does not re-pack assets. GGUF: `models/llm/*.gguf` via download-models.sh |

Если модели не найдены, pipeline **явно** идёт в stub-режим (помечается в UI). Stub не выдаётся за ASR на защите.

## Зависимости

- AndroidX Compose / Material3 / Activity / DocumentFile
- kotlinx.serialization (JSON)
- OkHttp (только PresentConnect / опциональный download)
- sherpa-onnx Android AAR from GitHub Releases v1.13.5 (JitPack `v2.0.1` unpublished; `scripts/fetch-sherpa-aar.sh`)
- (emergency) Vosk Android — подключается только если primary/backup недоступны

Явно **не** используем: Picovoice (AccessKey), whisper.cpp как primary, OEM `SpeechRecognizer`, облачные SpeechKit/Whisper API.

## Безопасность и приватность

- Офлайн-путь не отправляет аудио с устройства.
- PresentConnect шлёт PCM на телефон (worker) через BE команды; аудио не уходит в облачный ASR.
- Модели и веса — на устройстве к моменту проверки.
- Секреты не коммитим (`.env` в gitignore).
