#!/usr/bin/env python3
"""WER / RTF plus host-side diarization metrics (DER, JER, speaker purity).

No pyannote.metrics dependency. Numbers are only as honest as the (ref, hyp)
pair you pass in — this module does not invent scores.

DER: NIST-style (miss + false alarm + speaker error) / reference speaker-time,
optional collar around reference boundaries (default 250 ms). Overlapped speech
is counted per speaker (a 20-talker cocktail frame with 20 active refs has
weight 20).

JER: mean Jaccard error over reference speakers after Hungarian mapping;
unmapped hypothesis speakers are extra clusters with JER = 1.

Speaker purity: duration-weighted max-ref overlap / hyp duration. Used when
a full DER tool is unavailable; we still compute DER here.

WER / tokenize match overnight scripts/eval_harness.py (Russian ё→е).
"""
from __future__ import annotations

from typing import Iterable

Utterance = dict


def tokenize(text: str) -> list[str]:
    t = text.lower().replace("ё", "е")
    for ch in ".,!?;:«»\"'()[]":
        t = t.replace(ch, " ")
    return [w for w in t.split() if w]


def levenshtein(a: list[str], b: list[str]) -> int:
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            ins, delete, sub = cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (ca != cb)
            cur.append(min(ins, delete, sub))
        prev = cur
    return prev[-1]


def wer(ref: str, hyp: str) -> float | None:
    r = tokenize(ref)
    if not r:
        return None
    return levenshtein(r, tokenize(hyp)) / len(r)


def utterance_text(doc: dict, speaker_id: str | None = None) -> str:
    rows = doc.get("transcript") or []
    if speaker_id is None:
        return " ".join(str(u.get("text", "")) for u in rows)
    return " ".join(str(u.get("text", "")) for u in rows if u.get("speaker_id") == speaker_id)


def wav_duration_ms(path) -> int | None:
    from pathlib import Path

    try:
        data = Path(path).read_bytes()
    except OSError:
        return None
    if len(data) < 44 or data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        return None
    pos = 12
    sample_rate = bits = channels = None
    data_size = None
    while pos + 8 <= len(data):
        cid = data[pos : pos + 4]
        size = int.from_bytes(data[pos + 4 : pos + 8], "little")
        body = pos + 8
        if cid == b"fmt ":
            channels = int.from_bytes(data[body + 2 : body + 4], "little")
            sample_rate = int.from_bytes(data[body + 4 : body + 8], "little")
            bits = int.from_bytes(data[body + 14 : body + 16], "little")
        elif cid == b"data":
            data_size = size
            break
        pos = body + size + (size & 1)
    if not all((sample_rate, bits, channels, data_size)):
        return None
    bytes_per_sec = sample_rate * channels * (bits // 8)
    if not bytes_per_sec:
        return None
    return int(data_size * 1000 / bytes_per_sec)


def _hungarian_maximize(weights: list[list[float]]) -> list[tuple[int, int]]:
    """Maximum-weight assignment. Square-pads with zeros. Pure Python."""
    n0 = len(weights)
    m0 = len(weights[0]) if weights else 0
    n = max(n0, m0)
    if n == 0:
        return []
    wmax = 0.0
    for row in weights:
        for v in row:
            if v > wmax:
                wmax = v
    cost = [[wmax] * n for _ in range(n)]
    for i, row in enumerate(weights):
        for j, v in enumerate(row):
            cost[i][j] = wmax - v

    u = [0.0] * (n + 1)
    v = [0.0] * (n + 1)
    p = [0] * (n + 1)
    way = [0] * (n + 1)
    for i in range(1, n + 1):
        p[0] = i
        minv = [float("inf")] * (n + 1)
        used = [False] * (n + 1)
        j0 = 0
        while True:
            used[j0] = True
            i0 = p[j0]
            delta = float("inf")
            j1 = 0
            for j in range(1, n + 1):
                if used[j]:
                    continue
                cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                if cur < minv[j]:
                    minv[j] = cur
                    way[j] = j0
                if minv[j] < delta:
                    delta = minv[j]
                    j1 = j
            for j in range(n + 1):
                if used[j]:
                    u[p[j]] += delta
                    v[j] -= delta
                else:
                    minv[j] -= delta
            j0 = j1
            if p[j0] == 0:
                break
        while True:
            j1 = way[j0]
            p[j0] = p[j1]
            j0 = j1
            if j0 == 0:
                break
    pairs = []
    for j in range(1, n + 1):
        i = p[j]
        if i <= n0 and j <= m0:
            pairs.append((i - 1, j - 1))
    return pairs


def _segments(doc: dict) -> list[tuple[int, int, str]]:
    out = []
    for u in doc.get("transcript") or []:
        sid = str(u.get("speaker_id", "")).strip()
        if not sid:
            continue
        start = int(u["start_ms"])
        end = int(u["end_ms"])
        if end < start:
            start, end = end, start
        out.append((start, end, sid))
    return out


def _max_end(segs: Iterable[tuple[int, int, str]]) -> int:
    m = 0
    for _s, e, _id in segs:
        if e > m:
            m = e
    return m


def _collar_mask(ref: list[tuple[int, int, str]], n_frames: int, frame_ms: int, collar_ms: int) -> list[bool]:
    """True = evaluate this frame. False = inside collar of a ref boundary."""
    if collar_ms <= 0:
        return [True] * n_frames
    c = collar_ms
    bounds = []
    for s, e, _sid in ref:
        bounds.append(s)
        bounds.append(e)
    eval_ok = [True] * n_frames
    for b in bounds:
        lo = max(0, b - c)
        hi = b + c
        a = lo // frame_ms
        z = min(n_frames, (hi + frame_ms - 1) // frame_ms)
        for i in range(a, z):
            eval_ok[i] = False
    return eval_ok


def _frame_sets(
    segs: list[tuple[int, int, str]], n_frames: int, frame_ms: int
) -> list[set[str]]:
    frames: list[set[str]] = [set() for _ in range(n_frames)]
    for s, e, sid in segs:
        a = max(0, s // frame_ms)
        z = min(n_frames, (e + frame_ms - 1) // frame_ms)
        for i in range(a, z):
            frames[i].add(sid)
    return frames


def overlap_matrix(
    ref: list[tuple[int, int, str]],
    hyp: list[tuple[int, int, str]],
    frame_ms: int = 10,
) -> tuple[list[str], list[str], list[list[float]]]:
    n_frames = (_max_end(ref + hyp) + frame_ms - 1) // frame_ms
    n_frames = max(n_frames, 1)
    ref_ids = sorted({s for _, _, s in ref})
    hyp_ids = sorted({s for _, _, s in hyp})
    rf = _frame_sets(ref, n_frames, frame_ms)
    hf = _frame_sets(hyp, n_frames, frame_ms)
    r_index = {s: i for i, s in enumerate(ref_ids)}
    h_index = {s: i for i, s in enumerate(hyp_ids)}
    mat = [[0.0] * len(hyp_ids) for _ in ref_ids]
    dt = frame_ms / 1000.0
    for t in range(n_frames):
        if not rf[t] or not hf[t]:
            continue
        for rs in rf[t]:
            ri = r_index[rs]
            for hs in hf[t]:
                mat[ri][h_index[hs]] += dt
    return ref_ids, hyp_ids, mat


def map_speakers(ref_doc: dict, hyp_doc: dict, frame_ms: int = 10) -> dict[str, str]:
    """hyp speaker_id → ref speaker_id (partial; unmatched hyp omitted)."""
    ref_ids, hyp_ids, mat = overlap_matrix(_segments(ref_doc), _segments(hyp_doc), frame_ms)
    if not ref_ids or not hyp_ids:
        return {}
    pairs = _hungarian_maximize(mat)
    mapping = {}
    for ri, hj in pairs:
        if mat[ri][hj] > 0:
            mapping[hyp_ids[hj]] = ref_ids[ri]
    return mapping


def diarization_scores(
    ref_doc: dict,
    hyp_doc: dict,
    *,
    frame_ms: int = 10,
    collar_ms: int = 250,
) -> dict:
    """Return DER / JER / purity plus components. Values are floats or None."""
    ref = _segments(ref_doc)
    hyp = _segments(hyp_doc)
    n_frames = (_max_end(ref + hyp) + frame_ms - 1) // frame_ms
    n_frames = max(n_frames, 1)
    eval_ok = _collar_mask(ref, n_frames, frame_ms, collar_ms)
    rf = _frame_sets(ref, n_frames, frame_ms)
    hf = _frame_sets(hyp, n_frames, frame_ms)
    mapping = map_speakers(ref_doc, hyp_doc, frame_ms)

    miss = fa = spk_err = 0.0
    total_ref = 0.0
    dt = frame_ms / 1000.0

    def mapped_hyp(hraw: set[str]) -> set[str]:
        hset = set()
        for hs in hraw:
            if hs in mapping:
                hset.add(mapping[hs])
            else:
                hset.add(f"__unmapped_{hs}")
        return hset

    for t in range(n_frames):
        if not eval_ok[t]:
            continue
        rset = rf[t]
        hset = mapped_hyp(hf[t])
        nref = len(rset)
        nhyp = len(hset)
        ncorr = len(rset & hset)
        total_ref += nref * dt
        miss += max(0, nref - nhyp) * dt
        fa += max(0, nhyp - nref) * dt
        spk_err += (min(nref, nhyp) - ncorr) * dt

    der = (miss + fa + spk_err) / total_ref if total_ref > 0 else None

    # Uncollared DER (same mapping) for cocktail-party where 250 ms collar
    # around every boundary can eat most of a short mix.
    miss0 = fa0 = err0 = tot0 = 0.0
    for t in range(n_frames):
        rset = rf[t]
        hset = mapped_hyp(hf[t])
        nref = len(rset)
        nhyp = len(hset)
        ncorr = len(rset & hset)
        tot0 += nref * dt
        miss0 += max(0, nref - nhyp) * dt
        fa0 += max(0, nhyp - nref) * dt
        err0 += (min(nref, nhyp) - ncorr) * dt
    der_no_collar = (miss0 + fa0 + err0) / tot0 if tot0 > 0 else None

    ref_ids = sorted({s for _, _, s in ref})
    hyp_ids = sorted({s for _, _, s in hyp})
    inv = {}
    for h, r in mapping.items():
        inv.setdefault(r, []).append(h)

    jer_terms = []
    for rs in ref_ids:
        inter = 0.0
        union = 0.0
        mapped = set(inv.get(rs, []))
        for t in range(n_frames):
            in_r = rs in rf[t]
            in_h = bool(hf[t] & mapped)
            if in_r and in_h:
                inter += dt
            if in_r or in_h:
                union += dt
        if union > 0:
            jer_terms.append(1.0 - inter / union)
        else:
            jer_terms.append(0.0)
    unmatched_hyp = [h for h in hyp_ids if h not in mapping]
    jer_terms.extend([1.0] * len(unmatched_hyp))
    jer = sum(jer_terms) / len(jer_terms) if jer_terms else None

    purity_num = 0.0
    purity_den = 0.0
    _, _, mat = overlap_matrix(ref, hyp, frame_ms)
    h_index = {s: i for i, s in enumerate(sorted({s for _, _, s in hyp}))}
    r_ids = sorted({s for _, _, s in ref})
    hyp_dur = {s: 0.0 for s in h_index}
    for t in range(n_frames):
        for hs in hf[t]:
            hyp_dur[hs] = hyp_dur.get(hs, 0.0) + dt
    for hs, dur in hyp_dur.items():
        if dur <= 0 or hs not in h_index or not r_ids:
            continue
        col = h_index[hs]
        best = max((mat[ri][col] for ri in range(len(r_ids))), default=0.0)
        purity_num += best
        purity_den += dur
    purity = purity_num / purity_den if purity_den > 0 else None

    return {
        "der": der,
        "der_no_collar": der_no_collar,
        "jer": jer,
        "speaker_purity": purity,
        "der_miss_s": miss,
        "der_fa_s": fa,
        "der_speaker_error_s": spk_err,
        "der_ref_speech_s": total_ref,
        "collar_ms": collar_ms,
        "frame_ms": frame_ms,
        "mapping_hyp_to_ref": mapping,
        "n_ref_speakers": len(ref_ids),
        "n_hyp_speakers": len(hyp_ids),
        "metric_impl": "scripts/eval_metrics.py (NIST-style DER, DIHARD-like JER, cluster purity); not pyannote.metrics",
    }


def main_speaker_wer(
    ref_doc: dict,
    hyp_doc: dict,
    mapping: dict[str, str] | None = None,
    main_id: str = "speaker_1",
) -> float | None:
    if mapping is None:
        mapping = map_speakers(ref_doc, hyp_doc)
    ref_txt = utterance_text(ref_doc, main_id)
    hyp_ids = [h for h, r in mapping.items() if r == main_id]
    rows = hyp_doc.get("transcript") or []
    hyp_txt = " ".join(str(u.get("text", "")) for u in rows if u.get("speaker_id") in hyp_ids)
    return wer(ref_txt, hyp_txt)
