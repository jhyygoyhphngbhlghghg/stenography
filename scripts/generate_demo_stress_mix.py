#!/usr/bin/env python3
"""Build the 20-talker cocktail-party fixture (SYNTHETIC).

Design (see samples/demo-stress/README.md):
  * 20 talkers, one mixed 16 kHz mono room wav
  * Main speaker (speaker_1 / PresentConnect stream s01) at about +3 dB SNR
    versus the SUM of the other 19 (measured on main-active frames)
  * 19 babble voices overlapping for the whole mix
  * Stream start offsets staggered (≤20 PresentConnect channels landing late)
  * Main speaker has THREE turns with gaps (stable-id tracking)

Synthesis: eSpeak NG Russian (`espeak-ng -v ru`) when available; otherwise a
labeled formant buzz (Zipformer will not recognize it — that is honest).
Not a real-speech corpus. No Common Voice / GOLOS weights are downloaded.

Usage:
  python3 scripts/generate_demo_stress_mix.py
  python3 scripts/generate_demo_stress_mix.py --smoke-only
"""
from __future__ import annotations

import argparse
import json
import math
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "samples" / "demo-stress"
HZ = 16000
MAIN_SNR_DB = 3.0
STAGGER_MS = 90  # 20 streams → 0 .. 1710 ms
SEED = 20261106

# Three turns so diarization has to keep the same id across gaps.
MAIN_TURNS = [
    {
        "start_ms": 400,
        "text": "Добрый день коллеги. Начинаем демонстрацию нейро стенографиста.",
    },
    {
        "start_ms": 5400,
        "text": "В этой комнате говорят двадцать человек одновременно.",
    },
    {
        "start_ms": 10200,
        "text": "Главный докладчик должен остаться слышимым плюс три децибела.",
    },
]

# 19 overlapping babble scripts (speakers 2..20). Unique on purpose.
BABBLE_TEXTS = [
    "Погода сегодня облачная температура около десяти градусов ветер слабый с севера.",
    "Москва Петербург Казань Новосибирск Екатеринбург Владивосток Калининград Сочи.",
    "Первый второй третий четвертый пятый шестой седьмой восьмой девятый десятый.",
    "Нужно проверить микрофоны и задержку каналов перед началом трансляции.",
    "Красный синий зелёный жёлтый белый чёрный серый оранжевый фиолетовый.",
    "Завтра встреча в девять утра не забудьте протокол и список вопросов.",
    "Река Волга течёт на юг через леса и степи к каспийскому морю.",
    "Алгоритм кластеризации путает голоса когда все говорят разом в зале.",
    "Купите хлеб молоко масло сыр яблоки чай сахар и пачку крупы.",
    "Считаем от двадцати вниз девятнадцать восемнадцать семнадцать шестнадцать.",
    "Презентация идёт по сети а стенограмма считается только на телефоне.",
    "Лес поле озеро гора долина остров полуостров пустыня тундра тайга.",
    "Пожалуйста говорите по очереди иначе распознавание сильно деградирует.",
    "Север юг запад восток северо запад юго восток северо восток юго запад.",
    "Лабораторный стенд без эмулятора андроид считает только хостовый линукс.",
    "Книга тетрадь ручка карандаш линейка ластик рюкзак дневник смена обуви.",
    "Один канал опаздывает на девяносто миллисекунд относительно первого.",
    "Тишина в зале нужна судьям но этот клип специально без тишины.",
    "Конец фрагмента болтовни девятнадцатого голоса в коктейльной смеси.",
]

SMOKE_WORDS = [
    "ноль",
    "один",
    "два",
    "три",
    "четыре",
    "пять",
    "шесть",
    "семь",
    "восемь",
    "девять",
    "десять",
    "двадцать",
    "тридцать",
    "сорок",
    "пятьдесят",
    "сто",
    "тысяча",
    "привет",
    "коллеги",
    "доклад",
]


def _have_espeak() -> bool:
    return shutil.which("espeak-ng") is not None or shutil.which("espeak") is not None


def _espeak_bin() -> str:
    return shutil.which("espeak-ng") or shutil.which("espeak") or ""


def write_wav(path: Path, samples: np.ndarray, sr: int = HZ) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    x = np.clip(np.asarray(samples, dtype=np.float32), -1.0, 1.0)
    pcm = (x * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def read_wav_mono(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        nch = w.getnchannels()
        sw = w.getsampwidth()
        n = w.getnframes()
        raw = w.readframes(n)
    if sw == 2:
        x = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    else:
        raise RuntimeError(f"unsupported sample width {sw} in {path}")
    if nch > 1:
        x = x.reshape(-1, nch).mean(axis=1)
    return x, sr


def resample_linear(x: np.ndarray, sr_in: int, sr_out: int) -> np.ndarray:
    if sr_in == sr_out or x.size == 0:
        return x.astype(np.float32)
    n_out = max(1, int(round(x.size * sr_out / sr_in)))
    t_in = np.linspace(0.0, 1.0, x.size, endpoint=False)
    t_out = np.linspace(0.0, 1.0, n_out, endpoint=False)
    return np.interp(t_out, t_in, x).astype(np.float32)


def rms(x: np.ndarray) -> float:
    if x.size == 0:
        return 0.0
    return float(np.sqrt(np.mean(np.square(x, dtype=np.float64))))


def peak_normalize(x: np.ndarray, peak: float = 0.89) -> np.ndarray:
    m = float(np.max(np.abs(x))) if x.size else 0.0
    if m <= 1e-9:
        return x
    return (x * (peak / m)).astype(np.float32)


def fade(x: np.ndarray, sr: int, ms: float = 8.0) -> np.ndarray:
    n = int(sr * ms / 1000.0)
    if n <= 0 or x.size < 2 * n:
        return x
    y = x.copy()
    ramp = np.linspace(0.0, 1.0, n, dtype=np.float32)
    y[:n] *= ramp
    y[-n:] *= ramp[::-1]
    return y


def synth_formant(text: str, pitch_hz: float, sr: int = HZ) -> np.ndarray:
    """Labeled fallback: not speech. Zipformer-ru will not decode this as Russian."""
    words = max(1, len(text.split()))
    dur = max(0.40, 0.22 * words)
    n = int(sr * dur)
    t = np.arange(n, dtype=np.float32) / sr
    am = 0.35 + 0.65 * (np.sin(2 * math.pi * (words / dur) * t) ** 2)
    f0 = float(pitch_hz)
    sig = (
        0.45 * np.sin(2 * math.pi * f0 * t)
        + 0.22 * np.sin(2 * math.pi * 2 * f0 * t)
        + 0.10 * np.sin(2 * math.pi * 3 * f0 * t)
        + 0.08 * np.sin(2 * math.pi * (700 + pitch_hz) * t)
        + 0.05 * np.sin(2 * math.pi * (1400 + 0.5 * pitch_hz) * t)
    ).astype(np.float32)
    sig *= am.astype(np.float32)
    sig = fade(sig, sr, 12.0)
    sig /= max(1e-6, float(np.max(np.abs(sig))))
    return (sig * 0.5).astype(np.float32)


def tts_espeak(text: str, pitch: int, speed: int, sr: int = HZ) -> np.ndarray:
    bin_ = _espeak_bin()
    with tempfile.TemporaryDirectory() as td:
        raw = Path(td) / "t.wav"
        cmd = [
            bin_,
            "-v",
            "ru",
            "-p",
            str(int(pitch)),
            "-s",
            str(int(speed)),
            "-w",
            str(raw),
            text,
        ]
        subprocess.check_call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        x, in_sr = read_wav_mono(raw)
    x = resample_linear(x, in_sr, sr)
    return fade(x, sr, 8.0)


def tts(text: str, pitch: int, speed: int, engine: str, sr: int = HZ) -> np.ndarray:
    if engine == "espeak-ng":
        return tts_espeak(text, pitch, speed, sr)
    pitch_hz = 90.0 + (pitch % 70)
    return synth_formant(text, pitch_hz, sr)


def normalize_rms(x: np.ndarray, target: float = 0.06) -> np.ndarray:
    r = rms(x)
    if r < 1e-8:
        return x
    return (x * (target / r)).astype(np.float32)


def loop_to(x: np.ndarray, n: int, sr: int) -> np.ndarray:
    if x.size == 0:
        return np.zeros(n, dtype=np.float32)
    if x.size >= n:
        return x[:n]
    reps = int(math.ceil(n / x.size))
    y = np.tile(x, reps)[:n].astype(np.float32)
    # tiny crossfade at original loop points
    fade_n = min(int(0.008 * sr), x.size // 4)
    if fade_n > 1:
        for k in range(1, reps):
            i = k * x.size
            if i >= n:
                break
            a = max(0, i - fade_n)
            b = min(n, i + fade_n)
            # already tiled; just smooth a few samples
            w = np.linspace(0.0, 1.0, b - a, dtype=np.float32)
            y[a:b] = y[a:b]  # kept; click is reduced by fade() on source
            _ = w
    return y


def voice_params(i: int) -> tuple[int, int]:
    pitch = 20 + (i * 7) % 65
    speed = 115 + (i * 11) % 55
    return pitch, speed


def place(canvas: np.ndarray, clip: np.ndarray, start_ms: int, sr: int = HZ) -> tuple[int, int]:
    start = int(round(start_ms * sr / 1000.0))
    if start < 0:
        clip = clip[-start:]
        start = 0
    end = min(canvas.size, start + clip.size)
    n = end - start
    if n <= 0:
        return start_ms, start_ms
    canvas[start:end] += clip[:n]
    end_ms = int(round(end * 1000.0 / sr))
    return int(round(start * 1000.0 / sr)), end_ms


def transcript_item(speaker_id: str, start_ms: int, end_ms: int, text: str) -> dict:
    return {
        "speaker_id": speaker_id,
        "start_ms": int(start_ms),
        "end_ms": int(end_ms),
        "text": text,
    }


def build_full(engine: str, rng: np.random.Generator | None = None) -> dict:
    """Return mix samples + ref doc + channel table + snr."""
    n_talkers = 20
    pitch0, speed0 = voice_params(0)
    main_clips = [normalize_rms(tts(t["text"], pitch0, speed0, engine)) for t in MAIN_TURNS]
    babble_clips = []
    for i, text in enumerate(BABBLE_TEXTS, start=1):
        pitch, speed = voice_params(i)
        babble_clips.append((i, text, pitch, speed, normalize_rms(tts(text, pitch, speed, engine))))

    # Place main turns without overlap (gaps so the same id must survive silence).
    main_plan = []
    cursor = MAIN_TURNS[0]["start_ms"]
    gap_ms = 400
    for turn, clip in zip(MAIN_TURNS, main_clips):
        start = max(int(turn["start_ms"]), cursor)
        dur_ms = int(round(clip.size * 1000.0 / HZ))
        main_plan.append((start, start + dur_ms, clip, turn["text"]))
        cursor = start + dur_ms + gap_ms
    last_main_end = main_plan[-1][1]
    longest_babble_ms = max(int(round(c.size * 1000.0 / HZ)) for *_rest, c in babble_clips)
    last_babble_ms = STAGGER_MS * (n_talkers - 1) + longest_babble_ms
    dur_ms = max(last_main_end, last_babble_ms) + 400
    n = int(dur_ms * HZ / 1000)
    dry = [np.zeros(n, dtype=np.float32) for _ in range(n_talkers)]
    channels = []
    utterances = []

    channels.append(
        {
            "stream_id": "s01",
            "speaker_id": "speaker_1",
            "offset_ms": 0,
            "role": "main",
            "pitch": pitch0,
            "speed": speed0,
        }
    )
    for start, _end, clip, text in main_plan:
        s, e = place(dry[0], clip, start)
        utterances.append(transcript_item("speaker_1", s, e, text))

    for i, text, pitch, speed, clip in babble_clips:
        offset = i * STAGGER_MS
        sid = f"speaker_{i + 1}"
        stream = f"s{i + 1:02d}"
        channels.append(
            {
                "stream_id": stream,
                "speaker_id": sid,
                "offset_ms": offset,
                "role": "babble",
                "pitch": pitch,
                "speed": speed,
            }
        )
        remain = n - int(offset * HZ / 1000)
        looped = loop_to(clip, max(remain, clip.size), HZ)
        s, e = place(dry[i], looped, offset)
        utterances.append(transcript_item(sid, s, e, text))

    main = dry[0]
    babble = np.zeros(n, dtype=np.float32)
    for i in range(1, n_talkers):
        babble += dry[i]

    # SNR on frames where main is active (abs > 1e-3 after dry RMS norm).
    active = np.abs(main) > 1e-3
    if not np.any(active):
        raise RuntimeError("main speaker canvas is silent")
    r_m = rms(main[active])
    r_b = rms(babble[active])
    if r_b < 1e-8:
        raise RuntimeError("babble is silent on main-active frames")
    target = r_b * (10 ** (MAIN_SNR_DB / 20.0))
    main_scaled = (main * (target / r_m)).astype(np.float32)
    mix = peak_normalize(main_scaled + babble)
    snr_db = 20.0 * math.log10(rms(main_scaled[active]) / max(1e-12, rms(babble[active])))

    # Re-scale dry[0] to match what went into the mix (before peak norm of sum).
    dry[0] = main_scaled
    peak = float(np.max(np.abs(main_scaled + babble)))
    if peak > 1e-9:
        g = 0.89 / peak
        mix = ((main_scaled + babble) * g).astype(np.float32)
        dry = [d * g for d in dry]

    utterances.sort(key=lambda u: (u["start_ms"], u["speaker_id"]))
    ref = {"transcript": utterances}
    return {
        "mix": mix,
        "dry": dry,
        "ref": ref,
        "channels": channels,
        "snr_db_measured": snr_db,
        "duration_ms": int(round(mix.size * 1000.0 / HZ)),
        "engine": engine,
    }


def build_smoke(engine: str) -> dict:
    n_talkers = 20
    stagger = 40
    word_ms = 280
    dur_ms = word_ms + stagger * (n_talkers - 1) + 80
    n = int(dur_ms * HZ / 1000)
    dry = [np.zeros(n, dtype=np.float32) for _ in range(n_talkers)]
    channels = []
    utterances = []
    for i, word in enumerate(SMOKE_WORDS):
        pitch, speed = voice_params(i)
        offset = i * stagger
        sid = f"speaker_{i + 1}"
        role = "main" if i == 0 else "babble"
        channels.append(
            {
                "stream_id": f"s{i + 1:02d}",
                "speaker_id": sid,
                "offset_ms": offset,
                "role": role,
                "pitch": pitch,
                "speed": speed,
            }
        )
        clip = normalize_rms(tts(word, pitch, speed, engine))
        # Keep smoke short: trim/pad to ~word_ms.
        want = int(word_ms * HZ / 1000)
        if clip.size > want:
            clip = clip[:want]
        s, e = place(dry[i], clip, offset)
        utterances.append(transcript_item(sid, s, e, word))
    main = dry[0]
    babble = np.zeros(n, dtype=np.float32)
    for i in range(1, n_talkers):
        babble += dry[i]
    active = np.abs(main) > 1e-3
    r_m = rms(main[active]) if np.any(active) else 1e-3
    r_b = rms(babble[active]) if np.any(active) else 1e-3
    target = r_b * (10 ** (MAIN_SNR_DB / 20.0))
    main_scaled = (main * (target / max(r_m, 1e-8))).astype(np.float32)
    peak = float(np.max(np.abs(main_scaled + babble))) or 1.0
    g = 0.89 / peak
    mix = ((main_scaled + babble) * g).astype(np.float32)
    snr_db = 20.0 * math.log10(
        rms(main_scaled[active]) / max(1e-12, rms(babble[active]))
    ) if np.any(active) else float("nan")
    utterances.sort(key=lambda u: (u["start_ms"], u["speaker_id"]))
    return {
        "mix": mix,
        "ref": {"transcript": utterances},
        "channels": channels,
        "snr_db_measured": snr_db,
        "duration_ms": int(round(mix.size * 1000.0 / HZ)),
        "engine": engine,
    }


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--out-dir", type=Path, default=OUT)
    p.add_argument("--smoke-only", action="store_true")
    p.add_argument("--full-only", action="store_true")
    p.add_argument("--force-formant", action="store_true", help="skip espeak even if installed")
    p.add_argument("--seed", type=int, default=SEED)
    args = p.parse_args()
    out: Path = args.out_dir
    out.mkdir(parents=True, exist_ok=True)
    (out / "dry").mkdir(exist_ok=True)
    (out / "ref").mkdir(exist_ok=True)

    if args.force_formant or not _have_espeak():
        engine = "formant-fallback"
        if not args.force_formant:
            print("espeak-ng not on PATH; using formant fallback (SYNTHETIC, not speech)", file=sys.stderr)
    else:
        engine = "espeak-ng"

    rng = np.random.default_rng(args.seed)
    meta = {
        "label": "SYNTHETIC",
        "description": "20-talker cocktail-party mix for host Linux ASR+diarization stress",
        "sample_rate_hz": HZ,
        "n_talkers": 20,
        "main_speaker_id": "speaker_1",
        "main_stream_id": "s01",
        "target_snr_db": MAIN_SNR_DB,
        "stagger_ms": STAGGER_MS,
        "engine": engine,
        "seed": args.seed,
        "licenses": {
            "audio": "synthetic; not a recorded corpus",
            "espeak-ng": "GPLv3 (renderer only; not redistributed)",
            "formant_fallback": "generated in this script",
        },
        "presentconnect_note": "offset_ms simulates ≤20 WS channels landing at different times",
        "host_linux_only": True,
        "no_kvm_adb": True,
    }

    if not args.full_only:
        smoke = build_smoke(engine)
        write_wav(out / "smoke.wav", smoke["mix"])
        (out / "ref" / "smoke.json").write_text(
            json.dumps(smoke["ref"], ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        meta["smoke"] = {
            "file": "samples/demo-stress/smoke.wav",
            "duration_ms": smoke["duration_ms"],
            "snr_db_measured": round(float(smoke["snr_db_measured"]), 3),
            "engine": smoke["engine"],
        }
        print(f"wrote {out / 'smoke.wav'} ({smoke['duration_ms']} ms, SNR {smoke['snr_db_measured']:.2f} dB)")

    if not args.smoke_only:
        full = build_full(engine, rng)
        write_wav(out / "mix_20talker.wav", full["mix"])
        for i, d in enumerate(full["dry"]):
            write_wav(out / "dry" / f"spk_{i + 1:02d}.wav", d)
        (out / "ref" / "mix_20talker.json").write_text(
            json.dumps(full["ref"], ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        main_only = {
            "transcript": [u for u in full["ref"]["transcript"] if u["speaker_id"] == "speaker_1"]
        }
        (out / "ref" / "mix_20talker_main.json").write_text(
            json.dumps(main_only, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        (out / "channels.json").write_text(
            json.dumps(
                {
                    "sample_rate_hz": HZ,
                    "n_talkers": 20,
                    "stagger_ms": STAGGER_MS,
                    "channels": full["channels"],
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        meta["full"] = {
            "file": "samples/demo-stress/mix_20talker.wav",
            "duration_ms": full["duration_ms"],
            "snr_db_measured": round(float(full["snr_db_measured"]), 3),
            "engine": full["engine"],
            "n_utterances_ref": len(full["ref"]["transcript"]),
        }
        print(
            f"wrote {out / 'mix_20talker.wav'} ({full['duration_ms']} ms, "
            f"SNR {full['snr_db_measured']:.2f} dB, engine={engine})"
        )

    (out / "mix_build.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"engine={engine}  (SYNTHETIC)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
