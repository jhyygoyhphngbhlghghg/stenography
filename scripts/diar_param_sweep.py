#!/usr/bin/env python3
"""Path A clustering sweep on the one-room mix (diarization only, no ASR).

Writes measured n_hyp_speakers + DER/JER for a small grid. Does not invent
numbers. Skip ASR so the sweep stays cheap.

Usage:
  python3 scripts/diar_param_sweep.py
"""
from __future__ import annotations

import json
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

WAV = ROOT / "samples" / "demo-stress" / "mix_20talker.wav"
REF = ROOT / "samples" / "demo-stress" / "ref" / "mix_20talker.json"
OUT = ROOT / "artifacts" / "eval" / "demo-stress" / "diar-sweep.json"
MODELS = ROOT / "models"

GRID = [
    {"num_clusters": 20, "cluster_threshold": 0.5, "min_duration_on": 0.2, "min_duration_off": 0.5},
    {"num_clusters": 20, "cluster_threshold": 0.3, "min_duration_on": 0.2, "min_duration_off": 0.5},
    {"num_clusters": 20, "cluster_threshold": 0.8, "min_duration_on": 0.2, "min_duration_off": 0.5},
    {"num_clusters": -1, "cluster_threshold": 0.5, "min_duration_on": 0.2, "min_duration_off": 0.5},
    {"num_clusters": -1, "cluster_threshold": 0.3, "min_duration_on": 0.1, "min_duration_off": 0.3},
    {"num_clusters": 20, "cluster_threshold": 0.5, "min_duration_on": 0.1, "min_duration_off": 0.2},
]


def main() -> int:
    if not WAV.is_file():
        print(f"BLOCKED: mix wav missing ({WAV}); generate first", file=sys.stderr)
        return 2
    if not REF.is_file():
        print(f"BLOCKED: ref missing ({REF})", file=sys.stderr)
        return 2
    ref = json.loads(REF.read_text(encoding="utf-8"))
    rows = []
    for cfg in GRID:
        rec = {
            "path": "A_one_room_mix",
            **cfg,
            "status": "pending",
            "n_hyp_speakers": "",
            "n_segments": "",
            "der": "",
            "der_no_collar": "",
            "jer": "",
            "speaker_purity": "",
            "wall_ms": "",
            "blocked_reason": "",
        }
        try:
            doc, meta = host_asr_diar.transcribe_file(
                WAV,
                MODELS,
                num_clusters=int(cfg["num_clusters"]),
                cluster_threshold=float(cfg["cluster_threshold"]),
                min_duration_on=float(cfg["min_duration_on"]),
                min_duration_off=float(cfg["min_duration_off"]),
                skip_asr=True,
            )
            diar = M.diarization_scores(ref, doc)
            rec.update(
                {
                    "status": "measured",
                    "n_hyp_speakers": diar["n_hyp_speakers"],
                    "n_segments": meta["n_segments"],
                    "der": None if diar["der"] is None else round(diar["der"], 4),
                    "der_no_collar": None if diar["der_no_collar"] is None else round(diar["der_no_collar"], 4),
                    "jer": None if diar["jer"] is None else round(diar["jer"], 4),
                    "speaker_purity": None if diar["speaker_purity"] is None else round(diar["speaker_purity"], 4),
                    "wall_ms": meta["wall_ms"],
                }
            )
            print(
                f"nc={cfg['num_clusters']} thr={cfg['cluster_threshold']} "
                f"on={cfg['min_duration_on']} off={cfg['min_duration_off']} "
                f"hyp_spk={rec['n_hyp_speakers']} segs={rec['n_segments']} "
                f"DER={rec['der']} JER={rec['jer']} wall_ms={rec['wall_ms']}"
            )
        except host_asr_diar.HostBlocked as e:
            rec["status"] = "blocked"
            rec["blocked_reason"] = str(e)
            print(f"BLOCKED {cfg}: {e}")
        except Exception as e:
            rec["status"] = "blocked"
            rec["blocked_reason"] = f"{type(e).__name__}: {e}"
            print(traceback.format_exc())
        rows.append(rec)
    payload = {
        "suite": "diar-sweep",
        "wav": str(WAV),
        "invented_metrics": False,
        "timestamp_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "note": "Diarization only (skip_asr). Path A one-room mix. Numbers measured.",
        "rows": rows,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
