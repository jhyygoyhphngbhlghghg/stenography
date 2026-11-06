#!/usr/bin/env python3
"""Python mirror of Impl VadDiarReconcile.kt @ 552b142 (PR #7).

Impl source (not cherry-picked onto this eval branch):
  HEAD  552b142898f3cf3a8bddb939f0864730dbafb261
  branch impl/far-field-6spk-demo
  files VadDiarReconcile.kt, FarFieldScene.kt, FarFieldVad.kt,
        VadDiarReconcileTest.kt

Recover quiet distant seats that pyannote dropped (STAGE6 s5 @ 5.4 m).
Orphan / energy-hole / split non-near. K=6. Near 3 m seat is protected.
PresentConnect (expected_speakers <= 0) leaves diar untouched.

Does not retune Eval DEMO_STAGE6 wavs. Soft-flag only.
"""
from __future__ import annotations

from typing import Any

import numpy as np

import farfield_gain as FF

IMPL_SECOND_PASS_HEAD = "552b142898f3cf3a8bddb939f0864730dbafb261"

STAGE6: dict[str, Any] = {
    "name": "DEMO_STAGE6",
    "expected_speakers": 6,
    "vad_threshold": 0.32,
    "vad_min_silence_sec": 0.18,
    "vad_min_speech_sec": 0.20,
    "vad_max_speech_sec": 30.0,
    "diar_min_duration_on_sec": 0.08,
    "diar_min_duration_off_sec": 0.18,
    "diar_cluster_threshold": 0.5,
    "asr_crop_pad_ms": 200,
    "orphan_min_ms": 400,
    "expand_if_coverage_below": 0.5,
    "expand_max_region_ms": 1500,
    "merge_gap_ms": 280,
}

PRESENT_CONNECT: dict[str, Any] = {
    "name": "presentConnect",
    "expected_speakers": -1,
    "orphan_min_ms": 10**18,
    "expand_if_coverage_below": 0.0,
    "expand_max_region_ms": 0,
    "merge_gap_ms": 0,
    "asr_crop_pad_ms": 0,
}


def _seg(speaker_index: int, start_ms: int, end_ms: int) -> dict[str, int]:
    return {
        "speaker_index": int(speaker_index),
        "start_ms": int(start_ms),
        "end_ms": int(end_ms),
    }


def _copy(s: dict[str, int]) -> dict[str, int]:
    return _seg(s["speaker_index"], s["start_ms"], s["end_ms"])


def region_ms(region: dict[str, int], hz: int) -> tuple[int, int]:
    if hz <= 0:
        return 0, 0
    return (
        int(region["start_sample"]) * 1000 // hz,
        int(region["end_sample"]) * 1000 // hz,
    )


def energy_speech_regions(samples, sample_rate_hz: int = FF.SAMPLE_RATE_HZ) -> list[dict[str, int]]:
    """FarFieldVad.speechRegions — FarFieldGain.findSegments as exclusive-end regions."""
    out = []
    for a, b in FF.find_segments(samples, sample_rate_hz):
        if b > a:
            out.append({"start_sample": int(a), "end_sample": int(b)})
    return out


def union_regions(a: list[dict[str, int]], b: list[dict[str, int]]) -> list[dict[str, int]]:
    if not a:
        return list(b)
    if not b:
        return list(a)
    all_r = sorted(list(a) + list(b), key=lambda r: r["start_sample"])
    out: list[dict[str, int]] = []
    cur = dict(all_r[0])
    for n in all_r[1:]:
        if n["start_sample"] <= cur["end_sample"]:
            cur["end_sample"] = max(cur["end_sample"], n["end_sample"])
        else:
            out.append(cur)
            cur = dict(n)
    out.append(cur)
    return out


def overlaps(a0: int, a1: int, b0: int, b1: int) -> bool:
    return a0 < b1 and b0 < a1


def overlap_ms(a0: int, a1: int, b0: int, b1: int) -> int:
    return max(0, min(a1, b1) - max(a0, b0))


def subtract(r0: int, r1: int, occupied: list[dict[str, int]]) -> list[tuple[int, int]]:
    cur = r0
    out: list[tuple[int, int]] = []
    hits = [s for s in occupied if overlaps(s["start_ms"], s["end_ms"], r0, r1)]
    hits.sort(key=lambda s: s["start_ms"])
    for s in hits:
        a = max(s["start_ms"], r0)
        b = min(s["end_ms"], r1)
        if a > cur:
            out.append((cur, a))
        cur = max(cur, b)
    if r1 > cur:
        out.append((cur, r1))
    return out


def relabel_by_appearance(segs: list[dict[str, int]]) -> list[dict[str, int]]:
    order: list[int] = []
    seen: set[int] = set()
    for s in sorted(segs, key=lambda x: x["start_ms"]):
        i = s["speaker_index"]
        if i not in seen:
            seen.add(i)
            order.append(i)
    mapping = {old: new for new, old in enumerate(order)}
    return [_seg(mapping[s["speaker_index"]], s["start_ms"], s["end_ms"]) for s in segs]


def merge_same_speaker(segs: list[dict[str, int]], gap_ms: int) -> list[dict[str, int]]:
    if len(segs) <= 1 or gap_ms <= 0:
        return sorted((_copy(s) for s in segs), key=lambda s: s["start_ms"])
    sorted_segs = sorted(segs, key=lambda s: (s["start_ms"], s["speaker_index"]))
    out: list[dict[str, int]] = []
    for s in sorted_segs:
        last = out[-1] if out else None
        if (
            last is not None
            and last["speaker_index"] == s["speaker_index"]
            and s["speaker_index"] >= 0
            and s["start_ms"] <= last["end_ms"] + gap_ms
        ):
            last["end_ms"] = max(last["end_ms"], s["end_ms"])
        else:
            out.append(_copy(s))
    return out


def _overlaps_near(a0: int, a1: int, near: dict[str, int] | None) -> bool:
    return near is not None and overlaps(a0, a1, near["start_ms"], near["end_ms"])


def _is_near_seat(s: dict[str, int], near: dict[str, int] | None) -> bool:
    return (
        near is not None
        and s["speaker_index"] == near["speaker_index"]
        and s["start_ms"] == near["start_ms"]
        and s["end_ms"] == near["end_ms"]
    )


def clip_away_from_near(s: dict[str, int], near: dict[str, int] | None) -> dict[str, int]:
    if near is None or _is_near_seat(s, near):
        return _copy(s)
    if not overlaps(s["start_ms"], s["end_ms"], near["start_ms"], near["end_ms"]):
        return _copy(s)
    if s["start_ms"] >= near["start_ms"] and s["end_ms"] <= near["end_ms"]:
        return _seg(s["speaker_index"], s["start_ms"], s["start_ms"])
    start = s["start_ms"]
    end = s["end_ms"]
    if start < near["start_ms"] and end > near["end_ms"]:
        start = near["end_ms"]
    elif start < near["end_ms"] and end > near["end_ms"]:
        start = near["end_ms"]
    elif end > near["start_ms"] and start < near["start_ms"]:
        end = near["start_ms"]
    return _seg(s["speaker_index"], start, end)


def _snap_tiny_endpoint(
    segs: list[dict[str, int]],
    r0: int,
    r1: int,
    scene: dict[str, Any],
    near: dict[str, int] | None,
) -> None:
    dur = r1 - r0
    if dur <= 0 or dur > int(scene["expand_max_region_ms"]):
        return
    hits = [(i, s) for i, s in enumerate(segs) if overlaps(s["start_ms"], s["end_ms"], r0, r1)]
    if len(hits) != 1:
        return
    ix, s = hits[0]
    if _is_near_seat(s, near):
        return
    cov = overlap_ms(s["start_ms"], s["end_ms"], r0, r1) / float(dur)
    if cov < float(scene["expand_if_coverage_below"]):
        segs[ix] = _seg(s["speaker_index"], min(s["start_ms"], r0), max(s["end_ms"], r1))


def _fill_energy_holes(
    segs: list[dict[str, int]],
    samples,
    hz: int,
    scene: dict[str, Any],
    near: dict[str, int] | None,
    start_orphan_id: int,
) -> int:
    orphan_id = start_orphan_id
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    duration_ms = 0 if hz <= 0 else int(x.size) * 1000 // hz
    holes = subtract(0, duration_ms, segs)
    orphan_min = int(scene["orphan_min_ms"])
    for h0, h1 in holes:
        if h1 - h0 < orphan_min:
            continue
        a = int(np.clip((h0 * hz) // 1000, 0, x.size))
        b = int(np.clip((h1 * hz) // 1000, a, x.size))
        if b - a < 2:
            continue
        crop = x[a:b]
        for r0, r1 in FF.find_segments(crop, hz):
            s0 = h0 + r0 * 1000 // hz
            s1 = h0 + r1 * 1000 // hz
            if s1 - s0 >= orphan_min and not _overlaps_near(s0, s1, near):
                segs.append(_seg(orphan_id, s0, s1))
                orphan_id -= 1
    return orphan_id


def _split_non_near_by_vad(
    segs: list[dict[str, int]],
    vad: list[dict[str, int]],
    hz: int,
    scene: dict[str, Any],
    near: dict[str, int] | None,
) -> None:
    extra: list[dict[str, int]] = []
    remove: list[dict[str, int]] = []
    orphan_id = min((s["speaker_index"] for s in segs), default=0) - 1
    if orphan_id >= 0:
        orphan_id = -1
    orphan_min = int(scene["orphan_min_ms"])
    for s in segs:
        if _is_near_seat(s, near):
            continue
        hits = []
        for r in vad:
            r0, r1 = region_ms(r, hz)
            if overlaps(s["start_ms"], s["end_ms"], r0, r1):
                hits.append(r)
        hits.sort(key=lambda r: r["start_sample"])
        if len(hits) < 2:
            continue
        remove.append(s)
        first = hits[0]
        f0, f1 = region_ms(first, hz)
        extra.append(
            _seg(s["speaker_index"], max(s["start_ms"], f0), min(s["end_ms"], f1))
        )
        for h in hits[1:]:
            a0, a1 = region_ms(h, hz)
            a = max(s["start_ms"], a0)
            b = min(s["end_ms"], a1)
            if b - a >= orphan_min and not _overlaps_near(a, b, near):
                extra.append(_seg(orphan_id, a, b))
                orphan_id -= 1
    remove_ids = {id(s) for s in remove}
    kept = [s for s in segs if id(s) not in remove_ids]
    segs.clear()
    segs.extend(kept)
    segs.extend(extra)


def run(
    vad: list[dict[str, int]],
    diar: list[dict[str, int]],
    hz: int,
    scene: dict[str, Any] | None = None,
    samples=None,
) -> list[dict[str, int]]:
    scene = scene or STAGE6
    if int(scene.get("expected_speakers") or 0) <= 0:
        return sorted((_copy(s) for s in diar), key=lambda s: s["start_ms"])
    near = min((_copy(s) for s in diar), key=lambda s: s["start_ms"]) if diar else None
    segs = [_copy(s) for s in diar]
    orphan_id = -1
    for region in vad:
        r0, r1 = region_ms(region, hz)
        for p0, p1 in subtract(r0, r1, segs):
            if p1 - p0 >= int(scene["orphan_min_ms"]) and not _overlaps_near(p0, p1, near):
                segs.append(_seg(orphan_id, p0, p1))
                orphan_id -= 1
        _snap_tiny_endpoint(segs, r0, r1, scene, near)
    if samples is not None and np.asarray(samples).size:
        orphan_id = _fill_energy_holes(segs, samples, hz, scene, near, orphan_id)
    _split_non_near_by_vad(segs, vad, hz, scene, near)
    clipped = []
    for s in segs:
        c = clip_away_from_near(s, near)
        if c["end_ms"] > c["start_ms"]:
            clipped.append(c)
    merged = merge_same_speaker(clipped, int(scene["merge_gap_ms"]))
    return relabel_by_appearance(merged)


def speaker_id(seg: dict[str, int]) -> str:
    return f"speaker_{seg['speaker_index'] + 1}"
