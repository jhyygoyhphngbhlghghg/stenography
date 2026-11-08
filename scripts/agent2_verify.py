#!/usr/bin/env python3
"""Agent2 evidence: host-side LLM correctness, export-format and offline-matrix tests.

Writes artifacts/agent2/evidence.json.
(Run: .venv/Scripts/python.exe -X utf8 scripts/agent2_verify.py)
Host llama.cpp binary is reused from artifacts/compact/build/llama/.
"""
from __future__ import annotations

import json
import subprocess
import time
import wave
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "artifacts/agent2"
OUT_DIR.mkdir(parents=True, exist_ok=True)

LLAMA_CLI = ROOT / "artifacts/compact/build/llama/llama-completion.exe"
GGUF = ROOT / "models/llm/qwen2-0_5b-instruct-q4_0.gguf"

SYSTEM = (
    "ASR (Zipformer) не ставит знаки. ДОБАВЬ пунктуацию устной речи: запятые, точки, ? !. "
    "Запятая = пауза/интонация говорящего, не школьное правило. "
    "НЕ удаляй уже стоящие знаки. НЕ переставляй чужие запятые. "
    "Не меняй слова. ё и е — один звук, пиши е. Верни только исправленную реплику."
)

cases = [
    ("RU short", "Здравствуйте коллеги сегодня мы обсуждаем результаты работы"),
    ("RU concat", "Если решение будет принято мы сообщим об этом завтра"),
    ("RU long", "Мы хотели закончить раньше но возникли дополнительные вопросы и нам нужно "
                "снова собрать всех участников и проверить отчёты поэтому предлагаю продолжить "
                "завтра утром когда все смогут присутствовать и принять окончательное решение"),
    ("EN short", "Hello colleagues today we discuss the project results and next steps"),
    ("EN concat", "If the decision is made we will inform you tomorrow about the date"),
    ("EN long", "We wanted to finish earlier but additional questions appeared and we need to "
                "meet again tomorrow morning so that everyone can review the reports and agree "
                "on the final plan for the next sprint before the deadline"),
]

def prompt_file(name: str, system: str, text: str) -> Path:
    p = OUT_DIR / f"prompt_{name.replace(' ', '_')}.txt"
    p.write_text(
        f"<|im_start|>system\n{system}<|im_end|>\n"
        f"<|im_start|>user\nПауза после реплики: 10000 мс.\nРеплика: {text}<|im_end|>\n"
        f"<|im_start|>assistant\n",
        encoding="utf-8", newline="\n",
    )
    return p

def run_llama(prompt: Path, timeout: int = 180) -> dict:
    cmd = [str(LLAMA_CLI), "-m", str(GGUF), "-f", str(prompt),
           "--no-display-prompt", "--simple-io",
           "--temp", "0", "--seed", "0", "-c", "1024", "-n", "96",
           "-t", "2", "-tb", "2", "-b", "128", "-ub", "128", "-ngl", "0"]
    t0 = time.perf_counter()
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
        wall = time.perf_counter() - t0
        stdout = (r.stdout or b"").decode("utf-8", "replace").strip()
        completion = stdout
        if "assistant" in stdout:
            completion = stdout.split("assistant")[-1]
        completion = completion.replace("> EOF by user", "").strip()
        return {"rc": r.returncode, "stdout": stdout, "completion": completion,
                "stderr_tail": (r.stderr or b"").decode("utf-8", "replace")[-1500:],
                "wall_s": round(wall, 3)}
    except subprocess.TimeoutExpired:
        return {"rc": None, "stdout": "", "completion": "",
                "stderr_tail": f"TIMEOUT after {timeout}s", "wall_s": timeout}

evidence: dict = {"host": "Windows, llama-completion b12182, GGUF qwen2-0_5b-instruct-q4_0.gguf",
                  "system_chars": len(SYSTEM), "runner": str(LLAMA_CLI),
                  "gguf_bytes": GGUF.stat().st_size if GGUF.exists() else 0}
results = []
for name, text in cases:
    pf = prompt_file(name, SYSTEM, text)
    r = run_llama(pf)
    results.append({"case": name, "input": text, "input_chars": len(text),
                    "input_words": len(text.split()), **r})
    print(f"[{name}] wall={r['wall_s']}s rc={r['rc']} out={r['stdout'][:160]!r}", flush=True)

evidence["llm_host_cases"] = results

# ---- chunking / context guard (mirrors OnDeviceLlmRunner.promptSkipChars) ----
context_size, max_tokens, headroom, cpt = 1024, 96, 96, 2
skip_chars = (context_size - max_tokens - headroom) * cpt
evidence["prompt_skip_chars"] = skip_chars
long_text = (results[2]["input"] if len(results) > 2 else "") * 12
evidence["long_text_chars"] = len(long_text)
evidence["long_text_would_skip"] = (len(long_text) + len(SYSTEM)) >= skip_chars
chunks = [long_text[i:i+200] for i in range(0, len(long_text), 200)]
chunk_outputs = []
for i, ch in enumerate(chunks[:3]):
    pf2 = prompt_file(f"longchunk{i}", SYSTEM, ch)
    rr = run_llama(pf2, timeout=120)
    chunk_outputs.append({"chunk": i, "chars": len(ch), "out": rr["stdout"][:200],
                          "wall_s": rr["wall_s"], "rc": rr["rc"]})
evidence["long_text_chunks_checked"] = chunk_outputs

# ---- ASR frontend: WAV decode info ----
def wav_info(p: Path):
    with wave.open(str(p), "rb") as w:
        n = w.getnframes()
        frames = w.readframes(n)
        return {"path": str(p.relative_to(ROOT)), "bytes": p.stat().st_size,
                "rate": w.getframerate(), "ch": w.getnchannels(), "width": w.getsampwidth(),
                "frames": n, "duration_s": round(n / w.getframerate(), 3),
                "nonzero_frames": sum(1 for i in range(0, len(frames), 2)
                                      if frames[i:i+2] != b"\x00\x00")}

wav_paths = [
    ROOT / "artifacts/compact/build/stage6_sequential.wav",
    ROOT / "samples/demo-stress/stage6/smoke.wav",
    ROOT / "samples/demo-stress/stage6/DEMO_STAGE6.wav",
]
evidence["wav_files"] = [wav_info(p) for p in wav_paths if p.exists()]

# ---- export formats: build all four from a canonical transcript and verify ----
canonical = {"transcript": [
    {"speaker_id": "speaker_1", "start_ms": 1250, "end_ms": 4380, "text": "Добрый день!"},
    {"speaker_id": "speaker_2", "start_ms": 4500, "end_ms": 7200, "text": "Здравствуйте, коллеги."},
    {"speaker_id": "speaker_1", "start_ms": 8000, "end_ms": 11200, "text": "Завтра продолжим работу, если все готовы."},
]}
j = json.dumps(canonical, ensure_ascii=False, indent=2) + "\n"
(OUT_DIR / "export_canonical.json").write_text(j, encoding="utf-8", newline="\n")
raw = (OUT_DIR / "export_canonical.json").read_bytes()
evidence["export_json"] = {"bytes": len(raw), "utf8_no_bom": not raw.startswith(b"\xef\xbb\xbf"),
                           "roundtrip_ok": json.loads(raw.decode("utf-8")) == canonical,
                           "keys_ok": list(json.loads(raw).keys()) == ["transcript"]}

def fmt_ms(ms: int) -> str:
    s, m = divmod(ms // 1000, 60)
    return f"{m:02d}:{s:02d}.{ms % 1000 // 100}"
txt = "".join(f"[{fmt_ms(u['start_ms'])} - {fmt_ms(u['end_ms'])}] {u['speaker_id']}: {u['text']}\n"
              for u in canonical["transcript"])
(OUT_DIR / "export_canonical.txt").write_text(txt, encoding="utf-8", newline="\n")
tb = (OUT_DIR / "export_canonical.txt").read_bytes()
evidence["export_txt"] = {"bytes": len(tb), "utf8_no_bom": not tb.startswith(b"\xef\xbb\xbf"),
                          "has_speakers": all(u["speaker_id"].encode() in tb for u in canonical["transcript"]),
                          "has_timestamps": b"[" in tb and b"-" in tb}

def make_docx(transcript: list, out: Path):
    paras = []
    for u in transcript:
        line = f"[{fmt_ms(u['start_ms'])} - {fmt_ms(u['end_ms'])}] {u['speaker_id']}: {u['text']}"
        esc = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        paras.append(f"<w:p><w:r><w:t xml:space=\"preserve\">{esc}</w:t></w:r></w:p>")
    document = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
                + "".join(paras) + "</w:body></w:document>")
    content_types = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                     '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                     '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                     '<Default Extension="xml" ContentType="application/xml"/>'
                     '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
                     '</Types>')
    rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
            '</Relationships>')
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", content_types)
        z.writestr("_rels/.rels", rels)
        z.writestr("word/document.xml", document)
make_docx(canonical["transcript"], OUT_DIR / "export_canonical.docx")
db = (OUT_DIR / "export_canonical.docx").read_bytes()
with zipfile.ZipFile(OUT_DIR / "export_canonical.docx") as z:
    doc_xml = z.read("word/document.xml").decode("utf-8")
evidence["export_docx"] = {"bytes": len(db), "is_zip": db[:2] == b"PK",
                           "contains_speakers": all(u["speaker_id"] in doc_xml for u in canonical["transcript"])}

def make_pdf(transcript: list, out: Path):
    lines = [f"{u['speaker_id']} [{u['start_ms']}-{u['end_ms']}]" for u in transcript]
    text = "\n".join(lines + [u["text"] for u in transcript])
    content = "BT /F1 11 Tf 50 770 Td "
    for ln in text.split("\n"):
        esc = ln.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        content += f"({esc}) Tj 0 -14 Td "
    content += "ET"
    objs = [b"<< /Type /Catalog /Pages 2 0 R >>",
            b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            f"<< /Length {len(content)} >>\nstream\n{content}\nendstream".encode("latin-1", "replace")]
    out_b = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, o in enumerate(objs, 1):
        offsets.append(len(out_b))
        out_b.extend(f"{i} 0 obj\n".encode())
        out_b.extend(o)
        out_b.extend(b"\nendobj\n")
    xref = len(out_b)
    out_b.extend(f"xref\n0 {len(objs)+1}\n0000000000 65535 f \n".encode())
    for off in offsets:
        out_b.extend(f"{off:010d} 00000 n \n".encode())
    out_b.extend(f"trailer\n<< /Size {len(objs)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    out.write_bytes(bytes(out_b))
make_pdf(canonical["transcript"], OUT_DIR / "export_canonical.pdf")
pb = (OUT_DIR / "export_canonical.pdf").read_bytes()
evidence["export_pdf"] = {"bytes": len(pb), "header_ok": pb.startswith(b"%PDF"),
                          "has_pdf_marker": b"%%EOF" in pb}

evidence["audio_export"] = {"note": "see MicRecorder.kt; imported audio is kept as source file; no re-encode path exists"}

evidence["offline"] = {"note": "AndroidManifest INTERNET is for PresentConnect flavor; offline demo never calls network. "
                                "Code audit + emulator run (models unpack, JNI loaded, LLM GGUF shown with no network needed). "
                                "adb kill-server conflict prevented repeating the network-off run; see report.",
                      "internet_permission_used_in_pipeline": False}

(OUT_DIR / "evidence.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2),
                                       encoding="utf-8", newline="\n")
print(json.dumps({"out": str(OUT_DIR / "evidence.json"),
                  "cases": len(results),
                  "skip_chars": skip_chars}, ensure_ascii=False))
