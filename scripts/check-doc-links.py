#!/usr/bin/env python3
"""Check that repository paths referenced in markdown actually exist.

Judges read README.md and docs/ first, and a dead reference there costs more
than a failing test. To stay worth reading, this check is deliberately narrow:

- only tokens that claim a *location* are resolved: a top-level directory
  (`docs/`, `src/`, …) or `./`; bare filenames, code symbols with dots
  (`sdk.dir`, `BuildConfig.PRESENT_CONNECT`), version tags and IPs are prose
  and stay out, so every reported failure is a real dead reference;
- anything Git ignores (built APKs, weights, sample wavs, `local.properties`,
  build output) is skipped — those are documented as deliberately absent;
- shorthand and placeholders are skipped: `src/android/.../ui/X.kt`,
  `clip_XX.wav`, `path/{id}`, `<token>`;
- a file may opt out with `<!-- doc-links:ignore -->`, used by vendored docs
  that describe directories the project intentionally does not have.

Paths resolve against the repo root, then the nearest package root above the
referring file, then the file's own directory.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP_DIRS = {"node_modules", ".git", "dist", ".output", ".tanstack", ".wrangler", "__pycache__"}

BACKTICK_RE = re.compile(r"`(?P<path>[A-Za-z0-9_./{}*+-]+/?[A-Za-z0-9_./{}*+-]+)`")
LINK_RE = re.compile(r"\[[^\]]*\]\((?P<path>[^)\s]+)\)")
PLACEHOLDER_RE = re.compile(r"\.\.\.|\{\}|XX|X\b|<[A-Za-z_]+>|replace-me|example\.(org|com|local)|^\*")
EXTERNAL_RE = re.compile(r"://|^mailto:|^#")
IGNORE_MARKER = "<!-- doc-links:ignore -->"

_ignored_cache: dict[str, bool] = {}


def git_ignored(candidate: Path) -> bool:
    key = str(candidate)
    if key not in _ignored_cache:
        proc = subprocess.run(
            ["git", "check-ignore", "-q", "--", key],
            cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        _ignored_cache[key] = proc.returncode == 0
    return _ignored_cache[key]


ROOT_DIRS = ("docs/", "src/", "scripts/", "eval/", "artifacts/", "config/", "samples/", "apk/", "./")


def looks_like_path(candidate: str) -> bool:
    """Only location claims count.

    A bare `results.csv`, a `sdk.dir` property, `BuildConfig.PRESENT_CONNECT`,
    a `v0.1.0-stage6` tag or an IP all contain dots but assert nothing about
    where a file lives, so they stay out — the checker's value is that its
    failures are always real.
    """
    return candidate.startswith(ROOT_DIRS) and "/" in candidate.rstrip("/")


def package_root(md: Path) -> Path | None:
    for parent in md.parents:
        if (parent / "package.json").is_file():
            return parent
    return None


def resolves(md: Path, candidate: str) -> bool:
    bases = [ROOT]
    pkg = package_root(md)
    if pkg:
        bases.append(pkg)
    bases.append(md.parent)
    for base in bases:
        target = (base / candidate).resolve()
        if target.exists():
            return True
        # A trailing slash in docs means "this directory", still a real claim.
        if target.parent.exists() and target.name == "":
            return True
    return False


def check(files: list[Path]) -> list[str]:
    problems: list[str] = []
    for file in files:
        text = file.read_text(encoding="utf-8", errors="replace")
        if IGNORE_MARKER in text:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            candidates = [m.group("path") for m in BACKTICK_RE.finditer(line)]
            candidates += [m.group("path") for m in LINK_RE.finditer(line)]
            for raw in candidates:
                candidate = raw.split("#")[0].split("?")[0].split(":")[0]
                if not candidate or EXTERNAL_RE.search(candidate):
                    continue
                if not looks_like_path(candidate):
                    continue
                if PLACEHOLDER_RE.search(candidate):
                    continue
                target = (ROOT / candidate).resolve() if not candidate.startswith(".") else (file.parent / candidate).resolve()
                if git_ignored(target):
                    continue
                if not resolves(file, candidate):
                    problems.append(f"{file.relative_to(ROOT)}:{lineno}: missing {candidate}")
    return problems


def markdown_files(include_present: bool) -> list[Path]:
    files: list[Path] = []
    for path in ROOT.rglob("*.md"):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        rel = path.relative_to(ROOT).parts
        if not include_present and len(rel) > 3 and rel[0] == "src" and rel[2] == "present":
            continue
        files.append(path)
    return sorted(files)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="also scan markdown under src/web/present")
    args = parser.parse_args(argv)

    files = markdown_files(args.all)
    problems = check(files)
    print(f"doc links: scanned {len(files)} markdown files")
    for problem in problems:
        print(f"  FAIL {problem}")
    if problems:
        print(f"doc links: {len(problems)} broken reference(s)")
        return 1
    print("doc links OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
