#!/usr/bin/env python3
"""Trace the flat college mark into an exact SVG.

The source is a raster with a white background and two solid colors, and every
edge is straight. So instead of a general-purpose tracer, each colour region is
rebuilt from its per-row runs: the left boundary and the right boundary are
polylines, simplified with Ramer-Douglas-Peucker and closed into one polygon.
For straight-edged art this reproduces the shape exactly, and the printed
polygon-vs-pixel area check is what proves it.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image

COLORS = {"red": (0xCC, 0x23, 0x26), "blue": (0x00, 0x45, 0x72)}
TOLERANCE = 48          # distance in RGB space that still counts as the colour
EPSILON = 1.2           # DP simplification tolerance, in source pixels


def mask_for(rgb: np.ndarray, target: tuple[int, int, int]) -> np.ndarray:
    delta = rgb.astype(int) - np.array(target, dtype=int)
    return (np.abs(delta) ** 2).sum(axis=2) <= TOLERANCE ** 2


def components(mask: np.ndarray) -> list[np.ndarray]:
    """Connected regions of `mask` as boolean arrays (4-neighbour flood fill)."""
    seen = np.zeros_like(mask, dtype=bool)
    out: list[np.ndarray] = []
    ys, xs = np.nonzero(mask)
    for start_y, start_x in zip(ys.tolist(), xs.tolist()):
        if seen[start_y, start_x]:
            continue
        stack = [(start_y, start_x)]
        seen[start_y, start_x] = True
        region = np.zeros_like(mask, dtype=bool)
        region[start_y, start_x] = True
        while stack:
            y, x = stack.pop()
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < mask.shape[0] and 0 <= nx < mask.shape[1]:
                    if mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        region[ny, nx] = True
                        stack.append((ny, nx))
        out.append(region)
    return out


def runs_per_row(region: np.ndarray) -> list[tuple[int, int, int, bool]]:
    """One row of a region: its first and last pixel, plus a gap flag.

    A run of a straight-edged shape is contiguous; if it is not, the outline
    built from the extremes would cut across the gap, so the caller is told.
    """
    rows: list[tuple[int, int, int, bool]] = []
    for y in range(region.shape[0]):
        xs = np.nonzero(region[y])[0]
        if xs.size == 0:
            continue
        gaps = bool((xs[-1] - xs[0] + 1) != xs.size)
        rows.append((int(y), int(xs[0]), int(xs[-1]), gaps))
    return rows


def rdp(points: list[tuple[float, float]], epsilon: float) -> list[tuple[float, float]]:
    if len(points) < 3:
        return points
    start, end = points[0], points[-1]
    dx, dy = end[0] - start[0], end[1] - start[1]
    norm = (dx * dx + dy * dy) ** 0.5 or 1.0
    distances = [abs(dy * (px - start[0]) - dx * (py - start[1])) / norm for px, py in points[1:-1]]
    keep = int(np.argmax(distances)) + 1
    if distances[keep - 1] > epsilon:
        left = rdp(points[: keep + 1], epsilon)[:-1]
        right = rdp(points[keep:], epsilon)
        return left + right
    return [start, end]


def polygon_for(region: np.ndarray) -> tuple[list[tuple[float, float]], bool]:
    """Return the outline and whether any row of the region had a gap."""
    rows = runs_per_row(region)
    split = any(gap for _, _, _, gap in rows)
    left = [(float(x_start), float(row_y)) for row_y, x_start, _, _ in rows]
    right = [(float(x_end), float(row_y)) for row_y, _, x_end, _ in reversed(rows)]
    pts = rdp(left, EPSILON) + rdp(right, EPSILON)
    # Drop duplicated corner points where the two chains meet.
    clean: list[tuple[float, float]] = []
    for p in pts:
        if not clean or (abs(p[0] - clean[-1][0]) > 0.01 or abs(p[1] - clean[-1][1]) > 0.01):
            clean.append(p)
    return clean, split


def shoelace(points: list[tuple[float, float]]) -> float:
    total = 0.0
    for i, (x0, y0) in enumerate(points):
        x1, y1 = points[(i + 1) % len(points)]
        total += x0 * y1 - x1 * y0
    return abs(total) / 2


def svg_path(points: list[tuple[float, float]]) -> str:
    head, *tail = points
    parts = [f"M{head[0]:g},{head[1]:g}"]
    parts += [f"L{x:g},{y:g}" for x, y in tail]
    parts.append("Z")
    return "".join(parts)


def main(argv: list[str]) -> int:
    if len(argv) < 1:
        print("usage: trace-college-logo.py <source.png> [out.svg]", file=sys.stderr)
        return 2
    source = Path(argv[0])
    image = Image.open(source).convert("RGB")
    rgb = np.asarray(image)
    height, width = rgb.shape[:2]

    shapes: list[tuple[str, list[tuple[float, float]], int]] = []
    for name, target in COLORS.items():
        mask = mask_for(rgb, target)
        for region in components(mask):
            pixels = int(region.sum())
            if pixels < 200:                      # anti-aliasing specks
                continue
            points, split = polygon_for(region)
            shapes.append((name, points, pixels))
            area = shoelace(points)
            drift = abs(area - pixels) / max(area, 1)
            print(
                f"{name}: {pixels} px, {len(points)} vertices, polygon area {area:.0f}, "
                f"drift {drift * 100:.1f}%"
                + (", multi-run rows (hull fallback)" if split else ""),
            )
            if drift > 0.05:
                print(f"  WARNING: {name} outline does not match its pixels", file=sys.stderr)

    if not shapes:
        print("nothing traced", file=sys.stderr)
        return 1

    paths = "\n".join(
        '    <path fill="#{}" d="{}"/>'.format(
            "".join(f"{c:02x}" for c in COLORS[name]),
            svg_path(points),
        )
        for name, points, _ in shapes
    )
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" '
        f'role="img" aria-label="Логотип колледжа">\n{paths}\n</svg>\n'
    )

    out = Path(argv[1]) if len(argv) > 1 else source.with_suffix(".svg")
    out.write_text(svg, encoding="utf-8")
    print(f"wrote {out} ({len(svg)} bytes, {len(shapes)} shapes)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
