#!/usr/bin/env python3
"""Score the 20-talker cocktail-party fixture. Does not invent numbers.

Default: score existing hyp JSON (pending if missing).
--run-host: Linux sherpa-onnx Zipformer-ru + OfflineSpeakerDiarization.

Cloud agents have no KVM/ADB — this suite is host Linux only.

FROZEN Path B gate (docs/GATE.md, H1 accepted WORST_CASE @ 9908353):
  mix_20talker_per_stream, EDGE_reverb, EDGE_overlap, EDGE_noise,
  EDGE_badmic, WORST_CASE.
Fixtures change only with Lead sign-off. Do not retune to look pretty.
Re-run --run-host on frozen ids only when a harden fix is claimed.
Lock: 20/20 stream tracking holds under edge; do NOT claim clean WER/DER
on WORST_CASE.

Usage:
  python3 scripts/eval_demo_stress.py
  python3 scripts/eval_demo_stress.py --run-host
"""
from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
import traceback
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import eval_metrics as M  # noqa: E402
import host_asr_diar  # noqa: E402

MANIFEST = ROOT / "samples" / "demo-stress" / "manifest.json"
OUT_DIR = ROOT / "artifacts" / "eval" / "demo-stress"
VERIFY = SCRIPT_DIR / "verify-transcript-json.py"
# Frozen Path B regression suite. Do not drop these rows; do not retune fixtures.
FROZEN_PATH_B_GATE = (
    "mix_20talker_per_stream",
    "EDGE_reverb",
    "EDGE_overlap",
    "EDGE_noise",
    "EDGE_badmic",
    "WORST_CASE",
)


def _git_commit() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, text=True
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def _fmt(v) -> str:
    if v is None or v == "":
        return ""
    if isinstance(v, float):
        return f"{v:.4f}"
    return str(v)


def _load_manifest() -> dict:
    if MANIFEST.is_file():
        return json.loads(MANIFEST.read_text(encoding="utf-8"))
    # Generator-less fallback so the harness still emits blocked rows.
    return {
        "clips": [
            {
                "test_id": "mix_20talker",
                "file": "samples/demo-stress/mix_20talker.wav",
                "reference_json": "samples/demo-stress/ref/mix_20talker.json",
                "main_speaker_id": "speaker_1",
                "num_clusters": 20,
            },
            {
                "test_id": "mix_20talker_main_dry",
                "file": "samples/demo-stress/dry/spk_01.wav",
                "reference_json": "samples/demo-stress/ref/mix_20talker_main.json",
                "main_speaker_id": "speaker_1",
                "num_clusters": 1,
                "asr_only": True,
            },
            {
                "test_id": "smoke",
                "file": "samples/demo-stress/smoke.wav",
                "reference_json": "samples/demo-stress/ref/smoke.json",
                "main_speaker_id": "speaker_1",
                "num_clusters": 20,
            },
        ]
    }


def _verify(path: Path) -> list[str]:
    spec = importlib_verify()
    return spec(path)


def importlib_verify():
    import importlib.util

    spec = importlib.util.spec_from_file_location("verify_transcript_json", VERIFY)
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod.verify_file


def run_one(
    clip: dict,
    *,
    run_host: bool,
    models: Path,
    out_dir: Path,
    log,
) -> dict:
    test_id = clip["test_id"]
    wav = ROOT / clip["file"]
    ref_path = ROOT / clip["reference_json"]
    hyp_path = out_dir / f"{test_id}.json"
    wall_path = out_dir / f"{test_id}.wall_ms"
    meta_path = out_dir / f"{test_id}.meta.json"
    main_id = clip.get("main_speaker_id", "speaker_1")
    notes: list[str] = []
    status = "pending"
    blocked_reason = ""
    row = {
        "test_id": test_id,
        "timestamp_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "commit": _git_commit(),
        "file_name": clip["file"],
        "status": status,
        "duration_ms": "",
        "wall_ms": "",
        "rtf": "",
        "der": "",
        "der_no_collar": "",
        "jer": "",
        "speaker_purity": "",
        "wer_main": "",
        "wer_aggregate": "",
        "wer_per_speaker": "",
        "speakers_ref": "",
        "speakers_hyp": "",
        "json_valid": "",
        "notes": "",
        "blocked_reason": "",
        "metric_impl": "scripts/eval_metrics.py DER+JER+purity; WER Hex.Team keep ,.?!, fold ё→е",
        "stack": "linux-sherpa-onnx (no KVM/ADB)",
    }

    if not wav.is_file():
        notes.append("wav missing (run python3 scripts/generate_demo_stress_mix.py; full mix is gitignored)")
        if run_host and not clip.get("per_stream"):
            status = "blocked"
            blocked_reason = f"wav missing: {wav}"
    if not ref_path.is_file():
        notes.append("reference missing")
        status = "blocked"
        blocked_reason = blocked_reason or f"reference missing: {ref_path}"

    dur = M.wav_duration_ms(wav) if wav.is_file() else None
    if dur is None and (out_dir / f"{test_id}.meta.json").is_file():
        try:
            dur = int(json.loads((out_dir / f"{test_id}.meta.json").read_text()).get("duration_ms") or 0) or None
        except (OSError, ValueError, json.JSONDecodeError):
            dur = None
    if dur is not None:
        row["duration_ms"] = str(dur)

    if run_host and (wav.is_file() or clip.get("per_stream")):
        log(f"==> host inference {test_id} ({wav})")
        try:
            if clip.get("per_stream"):
                dry = ROOT / clip.get("dry_dir", "samples/demo-stress/dry")
                n = int(clip.get("n_talkers") or clip.get("num_clusters") or 20)
                doc, meta = host_asr_diar.transcribe_per_stream(
                    dry,
                    models,
                    n_talkers=n,
                    num_clusters=int(clip.get("num_clusters", 1)),
                    vad=str(clip.get("vad", "rms")),
                    pad_ms=int(clip.get("pad_ms", 80)),
                    min_duration_on=float(clip.get("min_duration_on", 0.15)),
                    min_duration_off=float(clip.get("min_duration_off", 0.25)),
                    rel_thresh=float(clip.get("rel_thresh", 0.04)),
                    pcm_gate=bool(clip.get("pcm_gate", False)),
                    pcm_gate_no_hush=bool(clip.get("pcm_gate_no_hush", False)),
                    pcm_gate_v2=bool(clip.get("pcm_gate_v2", False)),
                    pcm_gate_v3=bool(clip.get("pcm_gate_v3", False)),
                )
            else:
                doc, meta = host_asr_diar.transcribe_file(
                    wav,
                    models,
                    num_clusters=int(clip.get("num_clusters", 20)),
                    cluster_threshold=float(clip.get("cluster_threshold", 0.5)),
                    min_duration_on=float(clip.get("min_duration_on", 0.2)),
                    min_duration_off=float(clip.get("min_duration_off", 0.5)),
                    asr_only=bool(clip.get("asr_only", False)),
                    farfield_gain=bool(clip.get("farfield_gain", False)),
                    farfield_per_turn=bool(clip.get("farfield_per_turn", False)),
                    phone_distance_eq=bool(clip.get("phone_distance_eq", False)),
                    impl_second_pass=bool(clip.get("impl_second_pass", False)),
                )
            hyp_path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            wall_path.write_text(str(meta["wall_ms"]) + "\n", encoding="utf-8")
            meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            extra = ""
            if meta.get("pcm_gate") or meta.get("pcm_gate_v2"):
                extra = (
                    f" pcm_gate passed={meta.get('streams_passed')} "
                    f"dropped={meta.get('streams_dropped')} codes={meta.get('pcm_gate_codes')}"
                )
            if meta.get("farfield_gain") and meta.get("farfield"):
                ff = meta["farfield"]
                extra += (
                    f" ffgain[{ff.get('mode')}]={ff.get('gain_db'):.2f}dB "
                    f"nseg={ff.get('n_segments')} crushed={ff.get('loud_crushed')} "
                    f"rms {ff.get('rms_before'):.4f}->{ff.get('rms_after'):.4f} "
                    f"vad {ff.get('vad_fire_before'):.3f}->{ff.get('vad_fire_after'):.3f}"
                )
                if meta.get("phone_distance_eq"):
                    extra += (
                        f" phone_eq applied={ff.get('eq_applied')} "
                        f"n_applied={ff.get('eq_n_applied')} "
                        f"near_id={ff.get('eq_n_near_identity')} "
                        f"near_crushed={ff.get('eq_near_crushed')}"
                    )
            if meta.get("impl_second_pass") and meta.get("reconcile"):
                rc = meta["reconcile"]
                extra += (
                    f" recon in={rc.get('n_diar_in')}->{rc.get('n_diar_out')} "
                    f"spk {rc.get('n_hyp_speakers_in')}->{rc.get('n_hyp_speakers_out')} "
                    f"vad e={rc.get('n_energy_vad')} sil={rc.get('n_silero_vad')}"
                )
            if meta.get("intonation_punct"):
                extra += f" punct={meta['intonation_punct'].get('engine')}"
            log(f"    segments={meta['n_segments']} wall_ms={meta['wall_ms']}{extra}")
        except host_asr_diar.HostBlocked as e:
            status = "blocked"
            blocked_reason = str(e)
            notes.append(str(e))
            log(f"    BLOCKED: {e}")
        except MemoryError as e:
            status = "blocked"
            blocked_reason = f"OOM: {e}"
            notes.append(blocked_reason)
            log(f"    BLOCKED OOM: {e}")
        except Exception as e:
            status = "blocked"
            blocked_reason = f"{type(e).__name__}: {e}"
            notes.append(blocked_reason)
            log(traceback.format_exc())

    if not hyp_path.is_file():
        if status != "blocked":
            status = "pending"
            notes.append("hypothesis missing (pass --run-host on Linux)")
    else:
        errs = _verify(hyp_path)
        row["json_valid"] = "ok" if not errs else "fail"
        if errs:
            notes.extend(errs[:4])
        if ref_path.is_file():
            ref = json.loads(ref_path.read_text(encoding="utf-8"))
            hyp = json.loads(hyp_path.read_text(encoding="utf-8"))
            diar = M.diarization_scores(ref, hyp)
            mapping = diar["mapping_hyp_to_ref"]
            w = M.main_speaker_wer(ref, hyp, mapping, main_id=main_id)
            row["der"] = _fmt(diar["der"])
            row["der_no_collar"] = _fmt(diar["der_no_collar"])
            row["jer"] = _fmt(diar["jer"])
            row["speaker_purity"] = _fmt(diar["speaker_purity"])
            row["wer_main"] = _fmt(w)
            row["speakers_ref"] = str(diar["n_ref_speakers"])
            row["speakers_hyp"] = str(diar["n_hyp_speakers"])
            row["metric_impl"] = diar["metric_impl"]
            sw = None
            if clip.get("wer_per_speaker"):
                sw = M.speaker_wers(ref, hyp, mapping)
                row["wer_aggregate"] = _fmt(sw["wer_aggregate"])
                compact = {k: (None if v is None else round(float(v), 4)) for k, v in sw["per_speaker"].items()}
                row["wer_per_speaker"] = json.dumps(compact, ensure_ascii=False, separators=(",", ":"))
                (out_dir / f"{test_id}.speakers.json").write_text(
                    json.dumps(
                        {
                            "test_id": test_id,
                            "wer_aggregate": sw["wer_aggregate"],
                            "per_speaker": sw["per_speaker"],
                            "n_ref_words": sw["n_ref_words"],
                            "n_edits": sw["n_edits"],
                            "mapping_hyp_to_ref": mapping,
                        },
                        ensure_ascii=False,
                        indent=2,
                    )
                    + "\n",
                    encoding="utf-8",
                )
            if diar["n_hyp_speakers"] == 1 and diar["n_ref_speakers"] > 1:
                notes.append(
                    f"diarization collapsed to {diar['n_hyp_speakers']} speaker "
                    f"(ref {diar['n_ref_speakers']})"
                )
            if status != "blocked":
                status = "measured"
            diar_path = out_dir / f"{test_id}.diar.json"
            dump = {k: v for k, v in diar.items() if k != "mapping_hyp_to_ref"}
            dump["mapping_hyp_to_ref"] = mapping
            dump["wer_main"] = w
            if sw is not None:
                dump["wer_aggregate"] = sw["wer_aggregate"]
                dump["wer_per_speaker"] = sw["per_speaker"]
            diar_path.write_text(json.dumps(dump, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        wall = None
        if wall_path.is_file():
            try:
                wall = int(wall_path.read_text().strip())
            except ValueError:
                notes.append("wall_ms not an integer")
        if wall is not None:
            row["wall_ms"] = str(wall)
            if dur and dur > 0:
                row["rtf"] = _fmt(wall / dur)
            else:
                notes.append("RTF needs duration + wall_ms")
        elif status == "measured":
            notes.append("RTF needs wall_ms")
        if not row["der"] and row["der_no_collar"]:
            notes.append("collared DER undefined (250 ms collar ate all frames); see der_no_collar")

    row["status"] = status
    if meta_path.is_file():
        try:
            meta_doc = json.loads(meta_path.read_text(encoding="utf-8"))
        except (OSError, ValueError, json.JSONDecodeError):
            meta_doc = {}
        if meta_doc.get("pcm_gate") or meta_doc.get("pcm_gate_v2"):
            gate_note = (
                f"pcm_gate passed={meta_doc.get('streams_passed')}/"
                f"{int(meta_doc.get('streams_passed') or 0) + int(meta_doc.get('streams_dropped') or 0)} "
                f"dropped={meta_doc.get('streams_dropped')} codes={meta_doc.get('pcm_gate_codes')}"
            )
            if gate_note not in notes:
                notes.append(gate_note)
    clip_note = clip.get("notes") or ""
    if notes and clip_note and clip_note not in notes:
        notes.append(clip_note)
    row["notes"] = "; ".join(notes) if notes else clip_note
    row["blocked_reason"] = blocked_reason
    return row


class _Log:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self._fh = path.open("w", encoding="utf-8")

    def __call__(self, msg: str) -> None:
        line = msg if msg.endswith("\n") else msg + "\n"
        self._fh.write(line)
        self._fh.flush()
        sys.stdout.write(line)
        sys.stdout.flush()

    def close(self) -> None:
        self._fh.close()


def run_demo_stress(
    *,
    run_host: bool = False,
    models: Path | None = None,
    out_dir: Path | None = None,
    only: list[str] | None = None,
) -> int:
    out_dir = out_dir or OUT_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    models = models or (ROOT / "models")
    log = _Log(out_dir / "run.log")
    try:
        log(f"demo-stress eval  commit={_git_commit()}  utc={datetime.now(timezone.utc).isoformat()}")
        log("Cloud agents have no KVM/ADB — host Linux sherpa-onnx only.")
        log(f"run_host={run_host} only={only} models={models}")
        manifest = _load_manifest()
        rows = []
        print("test_id\tstatus\tder\tjer\tpurity\twer_main\trtf\tnotes")
        for clip in manifest["clips"]:
            do_host = run_host and (not only or clip["test_id"] in only)
            row = run_one(clip, run_host=do_host, models=models, out_dir=out_dir, log=log)
            rows.append(row)
            der_show = row["der"] or (f"uncollared:{row['der_no_collar']}" if row["der_no_collar"] else "-")
            print(
                f"{row['test_id']}\t{row['status']}\t{der_show}\t{row['jer'] or '-'}\t"
                f"{row['speaker_purity'] or '-'}\t{row['wer_main'] or '-'}\t{row['rtf'] or '-'}\t"
                f"{row['notes']}"
            )
            log(
                f"ROW {row['test_id']} status={row['status']} der={row['der']} jer={row['jer']} "
                f"purity={row['speaker_purity']} wer_main={row['wer_main']} rtf={row['rtf']} "
                f"blocked={row['blocked_reason']!r} notes={row['notes']!r}"
            )

        fields = [
            "test_id",
            "timestamp_utc",
            "commit",
            "file_name",
            "status",
            "duration_ms",
            "wall_ms",
            "rtf",
            "der",
            "der_no_collar",
            "jer",
            "speaker_purity",
            "wer_main",
            "wer_aggregate",
            "wer_per_speaker",
            "speakers_ref",
            "speakers_hyp",
            "json_valid",
            "notes",
            "blocked_reason",
            "metric_impl",
            "stack",
        ]
        csv_path = out_dir / "metrics.csv"
        with csv_path.open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
            w.writeheader()
            for r in rows:
                w.writerow(r)
        payload = {
            "suite": "demo-stress",
            "host_linux_only": True,
            "no_kvm_adb": True,
            "invented_metrics": False,
            "rows": rows,
        }
        (out_dir / "metrics.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        primary = out_dir / "mix_20talker.json"
        if primary.is_file():
            (out_dir / "hyp.json").write_text(primary.read_text(encoding="utf-8"), encoding="utf-8")
            log(f"copied {primary.name} -> hyp.json")
        log(f"wrote {csv_path} and {out_dir / 'metrics.json'}")
        # Non-zero only if JSON hyp exists and fails the verifier.
        for r in rows:
            if r.get("json_valid") == "fail":
                return 1
        return 0
    finally:
        log.close()


def _force_utf8_stdout() -> None:
    """Tables carry '−' and Cyrillic notes; a cp1251 console would abort the run."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):  # pragma: no cover - py<3.7 / redirected
            pass


def main() -> int:
    _force_utf8_stdout()
    p = argparse.ArgumentParser()
    p.add_argument("--run-host", action="store_true", help="run Linux sherpa-onnx (not Android)")
    p.add_argument("--models", type=Path, default=ROOT / "models")
    p.add_argument("--out-dir", type=Path, default=OUT_DIR)
    p.add_argument("--only", action="append", default=None, help="limit --run-host to these test_id values (repeatable)")
    args = p.parse_args()
    return run_demo_stress(
        run_host=args.run_host, models=args.models, out_dir=args.out_dir, only=args.only
    )


if __name__ == "__main__":
    raise SystemExit(main())
