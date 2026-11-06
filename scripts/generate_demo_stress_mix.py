#!/usr/bin/env python3
"""Build the 20-talker cocktail-party fixture from REAL Russian speech.

Default engine: Mozilla Common Voice 17.0 ru, test split, CC0-1.0
(`python3 scripts/fetch_cv17_ru.py` first). Gold refs are corpus `sentence`
fields, not eSpeak text.

Recipe (see samples/demo-stress/README.md):
  * 20 distinct client_id talkers
  * Main speaker_1 at about +3 dB SNR vs the SUM of the other 19
  * 19 babble voices overlapping; streams staggered (PresentConnect ≤20)
  * Main has THREE turns with gaps (stable-id tracking)

Historical `--engine espeak` remains for reproducing the superseded OOD
baseline. Headline eval is Common Voice.

Usage:
  python3 scripts/fetch_cv17_ru.py
  python3 scripts/generate_demo_stress_mix.py
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
CORPUS = OUT / "corpus" / "cv17-ru"
HZ = 16000
MAIN_SNR_DB = 3.0
STAGGER_MS = 90
SEED = 20261106
GAP_MS = 400

# eSpeak-only historical texts (not used for REAL gold).
MAIN_TURNS = [
    {"start_ms": 400, "text": "Добрый день коллеги. Начинаем демонстрацию нейро стенографиста."},
    {"start_ms": 5400, "text": "В этой комнате говорят двадцать человек одновременно."},
    {"start_ms": 10200, "text": "Главный докладчик должен остаться слышимым плюс три децибела."},
]
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
        raw = w.readframes(w.getnframes())
    if sw != 2:
        raise RuntimeError(f"unsupported sample width {sw} in {path}")
    x = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
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


def fade(x: np.ndarray, sr: int, ms: float = 8.0) -> np.ndarray:
    n = int(sr * ms / 1000.0)
    if n <= 0 or x.size < 2 * n:
        return x
    y = x.copy()
    ramp = np.linspace(0.0, 1.0, n, dtype=np.float32)
    y[:n] *= ramp
    y[-n:] *= ramp[::-1]
    return y


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
    return np.tile(x, reps)[:n].astype(np.float32)


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


def item(speaker_id: str, start_ms: int, end_ms: int, text: str) -> dict:
    return {
        "speaker_id": speaker_id,
        "start_ms": int(start_ms),
        "end_ms": int(end_ms),
        "text": text,
    }


def mix_snr(main: np.ndarray, babble: np.ndarray, snr_db: float) -> tuple[np.ndarray, np.ndarray, float, float]:
    active = np.abs(main) > 1e-3
    if not np.any(active):
        raise RuntimeError("main speaker canvas is silent")
    r_m = rms(main[active])
    r_b = rms(babble[active])
    if r_b < 1e-8:
        raise RuntimeError("babble is silent on main-active frames")
    target = r_b * (10 ** (snr_db / 20.0))
    main_scaled = (main * (target / r_m)).astype(np.float32)
    peak = float(np.max(np.abs(main_scaled + babble))) or 1.0
    g = 0.89 / peak
    mix = ((main_scaled + babble) * g).astype(np.float32)
    snr = 20.0 * math.log10(rms(main_scaled[active]) / max(1e-12, rms(babble[active])))
    return mix, main_scaled, snr, g


def load_cv_clip(selection: dict, talker: dict, clip: dict) -> np.ndarray:
    wav_dir = CORPUS / "wav"
    stem = Path(clip["path"]).stem
    path = wav_dir / f"{stem}.wav"
    if not path.is_file():
        raise FileNotFoundError(f"missing decoded clip {path} (run scripts/fetch_cv17_ru.py)")
    x, sr = read_wav_mono(path)
    x = resample_linear(x, sr, HZ)
    return fade(normalize_rms(x), HZ, 8.0)


def build_cv(selection: dict) -> dict:
    talkers = selection["talkers"]
    n_talkers = len(talkers)
    main = talkers[0]
    main_clips = [load_cv_clip(selection, main, c) for c in main["clips"][:3]]
    if len(main_clips) < 3:
        raise RuntimeError("main talker needs 3 corpus clips")
    babble_loaded = []
    for t in talkers[1:]:
        clip = load_cv_clip(selection, t, t["clips"][0])
        babble_loaded.append((t, clip))

    main_plan = []
    cursor = 400
    for clip, meta in zip(main_clips, main["clips"][:3]):
        dur_ms = int(round(clip.size * 1000.0 / HZ))
        main_plan.append((cursor, cursor + dur_ms, clip, meta["sentence"]))
        cursor = cursor + dur_ms + GAP_MS
    last_main_end = main_plan[-1][1]
    longest_b = max(c.size for _t, c in babble_loaded)
    last_babble_ms = STAGGER_MS * (n_talkers - 1) + int(round(longest_b * 1000.0 / HZ))
    dur_ms = max(last_main_end, last_babble_ms) + 400
    n = int(dur_ms * HZ / 1000)
    dry = [np.zeros(n, dtype=np.float32) for _ in range(n_talkers)]
    channels = []
    utterances = []

    channels.append(
        {
            "stream_id": main["stream_id"],
            "speaker_id": main["speaker_id"],
            "offset_ms": 0,
            "role": "main",
            "client_id": main["client_id"],
        }
    )
    for start, _end, clip, text in main_plan:
        s, e = place(dry[0], clip, start)
        utterances.append(item("speaker_1", s, e, text))

    for i, (t, clip) in enumerate(babble_loaded, start=1):
        offset = i * STAGGER_MS
        channels.append(
            {
                "stream_id": t["stream_id"],
                "speaker_id": t["speaker_id"],
                "offset_ms": offset,
                "role": "babble",
                "client_id": t["client_id"],
            }
        )
        remain = n - int(offset * HZ / 1000)
        looped = loop_to(clip, max(remain, clip.size), HZ)
        s, e = place(dry[i], looped, offset)
        utterances.append(item(t["speaker_id"], s, e, t["clips"][0]["sentence"]))

    main_a = dry[0]
    babble = np.zeros(n, dtype=np.float32)
    for i in range(1, n_talkers):
        babble += dry[i]
    mix, main_scaled, snr_db, g = mix_snr(main_a, babble, MAIN_SNR_DB)
    dry[0] = main_scaled
    dry = [d * g for d in dry]
    utterances.sort(key=lambda u: (u["start_ms"], u["speaker_id"]))
    return {
        "mix": mix,
        "dry": dry,
        "ref": {"transcript": utterances},
        "channels": channels,
        "snr_db_measured": snr_db,
        "duration_ms": int(round(mix.size * 1000.0 / HZ)),
        "engine": "common_voice_17_ru",
        "n_talkers": n_talkers,
    }


def build_cv_smoke(selection: dict) -> dict:
    talkers = selection["talkers"]
    n_talkers = len(talkers)
    stagger = 40
    crop_ms = 400
    dur_ms = crop_ms + stagger * (n_talkers - 1) + 80
    n = int(dur_ms * HZ / 1000)
    dry = [np.zeros(n, dtype=np.float32) for _ in range(n_talkers)]
    channels = []
    utterances = []
    want = int(crop_ms * HZ / 1000)
    for i, t in enumerate(talkers):
        clip = load_cv_clip(selection, t, t["clips"][0])
        if clip.size > want:
            mid = clip.size // 2
            a = max(0, mid - want // 2)
            clip = clip[a : a + want]
        offset = i * stagger
        channels.append(
            {
                "stream_id": t["stream_id"],
                "speaker_id": t["speaker_id"],
                "offset_ms": offset,
                "role": t["role"],
                "client_id": t["client_id"],
            }
        )
        s, e = place(dry[i], clip, offset)
        utterances.append(item(t["speaker_id"], s, e, t["clips"][0]["sentence"]))
    main = dry[0]
    babble = np.zeros(n, dtype=np.float32)
    for i in range(1, n_talkers):
        babble += dry[i]
    mix, _main_scaled, snr_db, _g = mix_snr(main, babble, MAIN_SNR_DB)
    utterances.sort(key=lambda u: (u["start_ms"], u["speaker_id"]))
    return {
        "mix": mix,
        "ref": {"transcript": utterances},
        "channels": channels,
        "snr_db_measured": snr_db,
        "duration_ms": int(round(mix.size * 1000.0 / HZ)),
        "engine": "common_voice_17_ru",
    }


def write_overnight_clips(selection: dict) -> None:
    """2–3 speaker turn-taking, no overlap, gold = corpus sentences."""
    t = selection["talkers"]
    a, b, c = t[0], t[1], t[2]
    a0 = load_cv_clip(selection, a, a["clips"][0])
    a1 = load_cv_clip(selection, a, a["clips"][1] if len(a["clips"]) > 1 else a["clips"][0])
    b0 = load_cv_clip(selection, b, b["clips"][0])
    c0 = load_cv_clip(selection, c, c["clips"][0])

    def concat_turns(turns: list[tuple[str, np.ndarray, str]], gap_ms: int) -> tuple[np.ndarray, dict]:
        gap = np.zeros(int(gap_ms * HZ / 1000), dtype=np.float32)
        parts = []
        utt = []
        t_ms = 0
        for sid, clip, text in turns:
            s = t_ms
            parts.append(clip)
            t_ms += int(round(clip.size * 1000.0 / HZ))
            utt.append(item(sid, s, t_ms, text))
            parts.append(gap)
            t_ms += gap_ms
        audio = np.concatenate(parts) if parts else np.zeros(1, dtype=np.float32)
        audio = fade(normalize_rms(audio, 0.08), HZ, 8.0)
        peak = float(np.max(np.abs(audio))) or 1.0
        audio = (audio * (0.89 / peak)).astype(np.float32)
        utt.sort(key=lambda u: (u["start_ms"], u["speaker_id"]))
        return audio, {"transcript": utt}

    samples_dir = ROOT / "samples"
    ref_dir = samples_dir / "ref"
    ref_dir.mkdir(parents=True, exist_ok=True)
    specs = [
        (
            "clip_01",
            [
                ("speaker_1", a0, a["clips"][0]["sentence"]),
                ("speaker_2", b0, b["clips"][0]["sentence"]),
                ("speaker_1", a1, a["clips"][1]["sentence"] if len(a["clips"]) > 1 else a["clips"][0]["sentence"]),
            ],
            300,
        ),
        (
            "clip_02",
            [
                ("speaker_1", b0, b["clips"][0]["sentence"]),
                ("speaker_2", c0, c["clips"][0]["sentence"]),
            ],
            1200,
        ),
        (
            "clip_03",
            [
                ("speaker_1", a0, a["clips"][0]["sentence"]),
                ("speaker_2", b0, b["clips"][0]["sentence"]),
                ("speaker_3", c0, c["clips"][0]["sentence"]),
            ],
            400,
        ),
    ]
    meta = []
    for name, turns, gap in specs:
        audio, ref = concat_turns(turns, gap)
        write_wav(samples_dir / f"{name}.wav", audio)
        (ref_dir / f"{name}.json").write_text(
            json.dumps(ref, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        meta.append({"test_id": name, "duration_ms": int(round(audio.size * 1000.0 / HZ)), "n_utt": len(ref["transcript"])})
        print(f"wrote {name}.wav ({meta[-1]['duration_ms']} ms, {meta[-1]['n_utt']} utt)")
    (samples_dir / "clips_build.json").write_text(
        json.dumps(
            {
                "label": "REAL",
                "corpus": selection["corpus"],
                "license": selection["license"],
                "notes": "Turn-taking, no overlap. Gold sentences from Common Voice test split.",
                "clips": meta,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def dump_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


# --- eSpeak historical path (superseded) -------------------------------------------------
def _have_espeak() -> bool:
    return shutil.which("espeak-ng") is not None or shutil.which("espeak") is not None


def tts_espeak(text: str, pitch: int, speed: int, sr: int = HZ) -> np.ndarray:
    bin_ = shutil.which("espeak-ng") or shutil.which("espeak")
    with tempfile.TemporaryDirectory() as td:
        raw = Path(td) / "t.wav"
        subprocess.check_call(
            [bin_, "-v", "ru", "-p", str(pitch), "-s", str(speed), "-w", str(raw), text],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        x, in_sr = read_wav_mono(raw)
    return fade(resample_linear(x, in_sr, sr), sr, 8.0)


def voice_params(i: int) -> tuple[int, int]:
    return 20 + (i * 7) % 65, 115 + (i * 11) % 55


def build_espeak() -> dict:
    """Kept only so `--engine espeak` can reproduce the superseded OOD baseline."""
    n_talkers = 20
    pitch0, speed0 = voice_params(0)
    main_clips = [normalize_rms(tts_espeak(t["text"], pitch0, speed0)) for t in MAIN_TURNS]
    babble_clips = []
    for i, text in enumerate(BABBLE_TEXTS, start=1):
        pitch, speed = voice_params(i)
        babble_clips.append((i, text, pitch, speed, normalize_rms(tts_espeak(text, pitch, speed))))
    main_plan = []
    cursor = MAIN_TURNS[0]["start_ms"]
    for turn, clip in zip(MAIN_TURNS, main_clips):
        start = max(int(turn["start_ms"]), cursor)
        dur_ms = int(round(clip.size * 1000.0 / HZ))
        main_plan.append((start, start + dur_ms, clip, turn["text"]))
        cursor = start + dur_ms + GAP_MS
    longest_babble_ms = max(int(round(c.size * 1000.0 / HZ)) for *_rest, c in babble_clips)
    dur_ms = max(main_plan[-1][1], STAGGER_MS * (n_talkers - 1) + longest_babble_ms) + 400
    n = int(dur_ms * HZ / 1000)
    dry = [np.zeros(n, dtype=np.float32) for _ in range(n_talkers)]
    channels = []
    utterances = []
    channels.append({"stream_id": "s01", "speaker_id": "speaker_1", "offset_ms": 0, "role": "main"})
    for start, _e, clip, text in main_plan:
        s, e = place(dry[0], clip, start)
        utterances.append(item("speaker_1", s, e, text))
    for i, text, pitch, speed, clip in babble_clips:
        offset = i * STAGGER_MS
        sid = f"speaker_{i + 1}"
        channels.append({"stream_id": f"s{i + 1:02d}", "speaker_id": sid, "offset_ms": offset, "role": "babble"})
        remain = n - int(offset * HZ / 1000)
        s, e = place(dry[i], loop_to(clip, max(remain, clip.size), HZ), offset)
        utterances.append(item(sid, s, e, text))
    babble = np.zeros(n, dtype=np.float32)
    for i in range(1, n_talkers):
        babble += dry[i]
    mix, main_scaled, snr_db, g = mix_snr(dry[0], babble, MAIN_SNR_DB)
    dry[0] = main_scaled
    dry = [d * g for d in dry]
    utterances.sort(key=lambda u: (u["start_ms"], u["speaker_id"]))
    return {
        "mix": mix,
        "dry": dry,
        "ref": {"transcript": utterances},
        "channels": channels,
        "snr_db_measured": snr_db,
        "duration_ms": int(round(mix.size * 1000.0 / HZ)),
        "engine": "espeak-ng",
        "n_talkers": n_talkers,
    }


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--out-dir", type=Path, default=OUT)
    p.add_argument("--engine", choices=("cv17", "espeak"), default="cv17")
    p.add_argument("--smoke-only", action="store_true")
    p.add_argument("--full-only", action="store_true")
    p.add_argument("--skip-overnight-clips", action="store_true")
    args = p.parse_args()
    out: Path = args.out_dir
    out.mkdir(parents=True, exist_ok=True)
    (out / "dry").mkdir(exist_ok=True)
    (out / "ref").mkdir(exist_ok=True)

    if args.engine == "cv17":
        sel_path = CORPUS / "selection.json"
        if not sel_path.is_file():
            print("missing corpus selection; run: python3 scripts/fetch_cv17_ru.py", file=sys.stderr)
            return 2
        selection = json.loads(sel_path.read_text(encoding="utf-8"))
        label = "REAL"
        engine_name = "common_voice_17_ru"
        licenses = {
            "audio": "Mozilla Common Voice 17.0 Russian, test split",
            "license": "CC0-1.0",
            "source": selection.get("source"),
            "gold": "corpus sentence field (not eSpeak, not ASR)",
        }
        if not args.full_only:
            smoke = build_cv_smoke(selection)
            write_wav(out / "smoke.wav", smoke["mix"])
            dump_json(out / "ref" / "smoke.json", smoke["ref"])
            print(f"wrote smoke.wav ({smoke['duration_ms']} ms, SNR {smoke['snr_db_measured']:.2f} dB)")
        if not args.smoke_only:
            full = build_cv(selection)
            write_wav(out / "mix_20talker.wav", full["mix"])
            for i, d in enumerate(full["dry"]):
                write_wav(out / "dry" / f"spk_{i + 1:02d}.wav", d)
            dump_json(out / "ref" / "mix_20talker.json", full["ref"])
            dump_json(
                out / "ref" / "mix_20talker_main.json",
                {"transcript": [u for u in full["ref"]["transcript"] if u["speaker_id"] == "speaker_1"]},
            )
            dump_json(out / "channels.json", {"sample_rate_hz": HZ, "n_talkers": full["n_talkers"], "stagger_ms": STAGGER_MS, "channels": full["channels"]})
            print(f"wrote mix_20talker.wav ({full['duration_ms']} ms, SNR {full['snr_db_measured']:.2f} dB, talkers={full['n_talkers']})")
            if not args.skip_overnight_clips:
                write_overnight_clips(selection)
        dump_json(
            out / "mix_build.json",
            {
                "label": label,
                "engine": engine_name,
                "target_snr_db": MAIN_SNR_DB,
                "stagger_ms": STAGGER_MS,
                "seed": SEED,
                "n_talkers": selection["n_talkers"],
                "licenses": licenses,
                "host_linux_only": True,
                "no_kvm_adb": True,
                "supersedes": "eSpeak SYNTHETIC baseline (WER=1.0 OOD); see artifacts/eval/demo-stress/espeak-baseline/",
                "smoke": None
                if args.full_only
                else {
                    "duration_ms": smoke["duration_ms"],
                    "snr_db_measured": round(float(smoke["snr_db_measured"]), 3),
                },
                "full": None
                if args.smoke_only
                else {
                    "duration_ms": full["duration_ms"],
                    "snr_db_measured": round(float(full["snr_db_measured"]), 3),
                    "n_utterances_ref": len(full["ref"]["transcript"]),
                    "n_talkers": full["n_talkers"],
                },
            },
        )
        return 0

    if not _have_espeak():
        print("espeak-ng not installed", file=sys.stderr)
        return 2
    print("WARNING: --engine espeak is the superseded SYNTHETIC baseline", file=sys.stderr)
    full = build_espeak()
    write_wav(out / "mix_20talker.wav", full["mix"])
    dump_json(out / "ref" / "mix_20talker.json", full["ref"])
    print(f"wrote espeak mix ({full['duration_ms']} ms, SNR {full['snr_db_measured']:.2f} dB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
