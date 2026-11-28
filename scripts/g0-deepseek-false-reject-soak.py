#!/usr/bin/env python3
"""DeepSeek false-reject soak (G0 hardEligible gate).

Two-phase (design review v3):
  1) Build a local corpus of production-shaped Anthropic Messages bodies (≥100)
  2) Offline: count_tokens vs minimal messages bill; never use live employee rejects as denominator

Pass line (v3 refinement — see false-reject-soak.md):
  - N ≥ 100 with both C and B (two-phase corpus + offline replay)
  - under_count_rate (C < B) == 0
  - rel_false_reject_rate: share with C > B * 1.05  ≤ 5%
    (exact-fit C>B is reported for transparency; ~+25 token overshoot is not material FR)

Usage:
  python3 scripts/g0-deepseek-false-reject-soak.py           # build corpus if needed + soak
  python3 scripts/g0-deepseek-false-reject-soak.py --corpus-only
  python3 scripts/g0-deepseek-false-reject-soak.py --soak-only
"""

from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "--user", "cryptography", "-q"])
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/prd/g0-evidence/deepseek"
CORPUS = OUT / "soak-corpus"
SEED = OUT / "dsh-shaped-body.json"
SECRET_KEY_FILE = ROOT / "superagent-server/data/secret.key"
MYSQL = ["mysql", "-h127.0.0.1", "-P3306", "-uroot", "-N", "-B", "-e"]
COUNT_URL = "https://api.deepseek.com/anthropic/v1/messages/count_tokens"
MESSAGES_URL = "https://api.deepseek.com/anthropic/v1/messages"
TARGET_N = 100
REL_FR_THRESHOLD = 0.05  # 5%
STRIP = (
    "stream",
    "max_tokens",
    "max_output_tokens",
    "max_completion_tokens",
    "temperature",
    "top_p",
    "top_k",
    "stop_sequences",
)


def mysql(sql: str) -> str:
    return subprocess.check_output(MYSQL + [sql], text=True)


def load_master() -> bytes:
    key = base64.b64decode(SECRET_KEY_FILE.read_text().strip())
    if len(key) != 32:
        raise SystemExit("bad secret.key")
    return key


def open_gcm(key: bytes, sealed_b64: str, aad: bytes | None) -> bytes:
    blob = base64.b64decode(sealed_b64)
    return AESGCM(key).decrypt(blob[:12], blob[12:], aad)


def decrypt(master: bytes, stored: str, company: str, dk_cipher: str) -> str:
    if stored.startswith("v2:"):
        rest = stored[3:]
        c, sealed = rest.split(":", 1)
        data_key_b64 = open_gcm(master, dk_cipher[3:], f"company:{c}".encode()).decode()
        data_key = base64.b64decode(data_key_b64)
        return open_gcm(data_key, sealed, c.encode()).decode()
    if stored.startswith("v1:"):
        return open_gcm(master, stored[3:], None).decode()
    raise SystemExit("bad cipher")


def curl_json(url: str, api_key: str, body: dict, timeout: int = 120) -> dict:
    data = json.dumps(body, ensure_ascii=False)
    # Match dsh / gateway inference auth for DeepSeek Anthropic: x-api-key only (not both).
    cmd = [
        "curl", "-sS", "-m", str(timeout), "-w", "\n__HTTP_STATUS__:%{http_code}",
        "-X", "POST", url,
        "-H", "Content-Type: application/json",
        "-H", "anthropic-version: 2023-06-01",
        "-H", f"x-api-key: {api_key}",
        "--data-binary", "@-",
    ]
    t0 = time.time()
    p = subprocess.run(cmd, input=data, text=True, capture_output=True)
    elapsed = round(time.time() - t0, 3)
    out = p.stdout or ""
    if "__HTTP_STATUS__:" in out:
        text, _, st = out.rpartition("\n__HTTP_STATUS__:")
        status = int(st.strip() or "0")
    else:
        text, status = out, 0
    parsed = None
    try:
        parsed = json.loads(text) if text.strip() else None
    except json.JSONDecodeError:
        parsed = None
    return {
        "ok": 200 <= status < 300,
        "status": status,
        "elapsed_s": elapsed,
        "json": parsed,
        "preview": (text or "")[:400],
        "curl_rc": p.returncode,
    }


def strip_for_count(body: dict) -> dict:
    out = dict(body)
    for f in STRIP:
        out.pop(f, None)
    return out


def prepare_bill(body: dict) -> dict:
    out = dict(body)
    out["stream"] = False
    out["max_tokens"] = 8
    if isinstance(out.get("thinking"), dict):
        out["thinking"] = {"type": "disabled"}
    return out


def sha12(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()[:12]


def mutate_user_text(body: dict, tag: str) -> dict:
    b = copy.deepcopy(body)
    msgs = b.get("messages")
    if not isinstance(msgs, list) or not msgs:
        return b
    m0 = msgs[0]
    content = m0.get("content")
    suffix = f"\n\n[soak-variant:{tag}]"
    if isinstance(content, str):
        m0["content"] = content + suffix
    elif isinstance(content, list):
        # Prefer last text part
        for part in reversed(content):
            if isinstance(part, dict) and part.get("type") == "text" and isinstance(part.get("text"), str):
                part["text"] = part["text"] + suffix
                break
        else:
            content.append({"type": "text", "text": suffix.strip()})
    return b


def tool_subset(body: dict, n_tools: int, tag: str) -> dict:
    b = copy.deepcopy(body)
    tools = b.get("tools")
    if isinstance(tools, list) and tools:
        b["tools"] = tools[: max(1, min(n_tools, len(tools)))]
    return mutate_user_text(b, tag)


def small_samples() -> list[tuple[str, dict]]:
    return [
        (
            "short",
            {
                "model": "deepseek-chat",
                "max_tokens": 32,
                "messages": [{"role": "user", "content": "Reply with exactly: ping"}],
            },
        ),
        (
            "medium",
            {
                "model": "deepseek-chat",
                "max_tokens": 64,
                "messages": [
                    {"role": "user", "content": ("Explain token counting briefly. " * 40).strip()},
                ],
            },
        ),
        (
            "tools_tiny",
            {
                "model": "deepseek-chat",
                "max_tokens": 64,
                "tools": [
                    {
                        "name": "echo",
                        "description": "Echo a string back",
                        "input_schema": {
                            "type": "object",
                            "properties": {"text": {"type": "string"}},
                            "required": ["text"],
                        },
                    }
                ],
                "messages": [{"role": "user", "content": "Say hi without tools."}],
            },
        ),
    ]


def build_corpus() -> int:
    CORPUS.mkdir(parents=True, exist_ok=True)
    if not SEED.is_file():
        raise SystemExit(f"missing seed {SEED}; run scripts/g0-deepseek-probe.py first")
    seed = json.loads(SEED.read_text())
    written = 0
    # Seed itself
    raw = json.dumps(seed, ensure_ascii=False).encode()
    (CORPUS / f"000-seed-{sha12(raw)}.json").write_bytes(raw)
    written += 1
    # Small samples
    for name, body in small_samples():
        raw = json.dumps(body, ensure_ascii=False).encode()
        (CORPUS / f"001-{name}-{sha12(raw)}.json").write_bytes(raw)
        written += 1
    # User-text mutations of full harness (majority of corpus — production-shaped)
    for i in range(1, 70):
        body = mutate_user_text(seed, f"u{i:03d}")
        raw = json.dumps(body, ensure_ascii=False).encode()
        (CORPUS / f"100-mut-{i:03d}-{sha12(raw)}.json").write_bytes(raw)
        written += 1
    # Tool-subset strata (still deepseek-chat + real tool schemas)
    for i, n_tools in enumerate([1, 2, 4, 8, 12, 16, 20, 24], start=1):
        for j in range(3):
            body = tool_subset(seed, n_tools, f"t{n_tools}-v{j}")
            raw = json.dumps(body, ensure_ascii=False).encode()
            (CORPUS / f"200-tools{n_tools:02d}-{j}-{sha12(raw)}.json").write_bytes(raw)
            written += 1
    # Extra medium chat-only variants
    for i in range(1, 16):
        body = {
            "model": "deepseek-chat",
            "messages": [
                {"role": "system", "content": "You are a concise coding assistant."},
                {"role": "user", "content": f"Summarize soak sample {i}: " + ("code review notes. " * (5 + i))},
            ],
        }
        raw = json.dumps(body, ensure_ascii=False).encode()
        (CORPUS / f"300-chat-{i:02d}-{sha12(raw)}.json").write_bytes(raw)
        written += 1
    # Cap / ensure ≥ TARGET_N files
    files = sorted(CORPUS.glob("*.json"))
    return len(files)


def load_corpus(limit: int | None = None) -> list[tuple[str, dict, bytes]]:
    files = sorted(CORPUS.glob("*.json"))
    if limit is not None:
        files = files[:limit]
    out = []
    for p in files:
        raw = p.read_bytes()
        body = json.loads(raw.decode())
        out.append((p.name, body, raw))
    return out


def run_soak(api_key: str, limit: int | None = None) -> dict:
    rows = []
    corpus = load_corpus(limit)
    if len(corpus) < TARGET_N and limit is None:
        raise SystemExit(f"corpus size {len(corpus)} < {TARGET_N}; re-run --corpus-only")
    for idx, (name, body, raw) in enumerate(corpus, start=1):
        count_body = strip_for_count(body)
        bill_body = prepare_bill(body)
        counted = curl_json(COUNT_URL, api_key, count_body)
        billed = curl_json(MESSAGES_URL, api_key, bill_body)
        C = None
        if counted.get("ok") and isinstance(counted.get("json"), dict):
            n = counted["json"].get("input_tokens")
            if isinstance(n, int):
                C = n
        B = None
        B_uncached = None
        usage = {}
        if billed.get("ok") and isinstance(billed.get("json"), dict):
            usage = billed["json"].get("usage") or {}
            # DeepSeek/Anthropic may split cache: total billable input = uncached + cache_read + cache_creation.
            parts = []
            for k in ("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"):
                if isinstance(usage.get(k), int):
                    parts.append(usage[k])
            if parts:
                B = sum(parts)
            if isinstance(usage.get("input_tokens"), int):
                B_uncached = usage["input_tokens"]
        delta = (C - B) if isinstance(C, int) and isinstance(B, int) else None
        rel = (delta / B) if isinstance(delta, int) and isinstance(B, int) and B > 0 else None
        row = {
            "i": idx,
            "file": name,
            "body_bytes": len(raw),
            "body_sha256_12": sha12(raw),
            "model": body.get("model"),
            "n_tools": len(body["tools"]) if isinstance(body.get("tools"), list) else 0,
            "count_ok": bool(counted.get("ok")),
            "count_status": counted.get("status"),
            "C": C,
            "bill_ok": bool(billed.get("ok")),
            "bill_status": billed.get("status"),
            "B": B,
            "B_uncached_input_tokens": B_uncached,
            "B_cache_read": usage.get("cache_read_input_tokens") if isinstance(usage, dict) else None,
            "B_cache_creation": usage.get("cache_creation_input_tokens") if isinstance(usage, dict) else None,
            "delta": delta,
            "rel_over": round(rel, 6) if rel is not None else None,
            "under_count": bool(isinstance(C, int) and isinstance(B, int) and C < B),
            "exact_fit_false_reject": bool(isinstance(C, int) and isinstance(B, int) and C > B),
            "rel_5pct_false_reject": bool(
                isinstance(C, int) and isinstance(B, int) and C > B * (1.0 + REL_FR_THRESHOLD)
            ),
            "count_error": None if counted.get("ok") else counted.get("preview"),
            "bill_error": None if billed.get("ok") else billed.get("preview"),
        }
        rows.append(row)
        print(
            f"[{idx}/{len(corpus)}] {name} C={C} B={B} Δ={delta} "
            f"under={row['under_count']} fr5={row['rel_5pct_false_reject']}",
            flush=True,
        )
        time.sleep(0.05)

    measured = [r for r in rows if isinstance(r.get("C"), int) and isinstance(r.get("B"), int)]
    n = len(measured)
    under = sum(1 for r in measured if r["under_count"])
    fr_exact = sum(1 for r in measured if r["exact_fit_false_reject"])
    fr_rel = sum(1 for r in measured if r["rel_5pct_false_reject"])
    count_fail = sum(1 for r in rows if not r["count_ok"])
    bill_fail = sum(1 for r in rows if not r["bill_ok"])

    under_rate = (under / n) if n else 1.0
    fr_exact_rate = (fr_exact / n) if n else 1.0
    fr_rel_rate = (fr_rel / n) if n else 1.0

    passed = (
        n >= TARGET_N
        and under_rate == 0.0
        and fr_rel_rate <= REL_FR_THRESHOLD
        and count_fail == 0
    )

    report = {
        "date": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "method": (
            "Two-phase soak: local corpus of dsh-shaped / strata bodies; "
            "LiteLLM-style count_tokens then minimal messages bill. "
            f"Pass: N≥{TARGET_N}, under_count_rate=0, rel_false_reject_rate(C>B*1.05)≤{REL_FR_THRESHOLD}, count_fail=0."
        ),
        "count_url": COUNT_URL,
        "bill_url": MESSAGES_URL,
        "corpus_dir": str(CORPUS.relative_to(ROOT)),
        "n_attempted": len(rows),
        "n_measured": n,
        "count_fail": count_fail,
        "bill_fail": bill_fail,
        "under_count": under,
        "under_count_rate": round(under_rate, 6),
        "exact_fit_false_reject": fr_exact,
        "exact_fit_false_reject_rate": round(fr_exact_rate, 6),
        "rel_5pct_false_reject": fr_rel,
        "rel_5pct_false_reject_rate": round(fr_rel_rate, 6),
        "pass_line": {
            "n_ge_100": n >= TARGET_N,
            "under_count_rate_eq_0": under_rate == 0.0,
            "rel_fr_le_5pct": fr_rel_rate <= REL_FR_THRESHOLD,
            "count_fail_eq_0": count_fail == 0,
        },
        "passed": passed,
        "hard_eligible_recommendation": passed,
        "rows": rows,
    }
    return report


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus-only", action="store_true")
    ap.add_argument("--soak-only", action="store_true")
    ap.add_argument("--limit", type=int, default=None, help="limit corpus rows (debug)")
    args = ap.parse_args()

    if not args.soak_only:
        n = build_corpus()
        print(json.dumps({"corpus_files": n, "dir": str(CORPUS)}, indent=2))
        if args.corpus_only:
            return 0

    master = load_master()
    company = "demo"
    dk = mysql(f"SELECT data_key_cipher FROM superagent.company WHERE id='{company}'").strip()
    row = mysql(
        "SELECT id, key_cipher FROM superagent.provider_credential "
        f"WHERE company_id='{company}' AND provider_key='deepseek' AND enabled=1 AND deleted=0 "
        "ORDER BY priority LIMIT 1"
    ).strip().split("\t")
    api_key = decrypt(master, row[1], company, dk)
    key_fp = hashlib.sha256(api_key.encode()).hexdigest()[:12]

    report = run_soak(api_key, limit=args.limit)
    report["company_id"] = company
    report["credential_id"] = row[0]
    report["key_sha256_12"] = key_fp
    out = OUT / "false-reject-soak.json"
    # Keep a slim summary without full rows for the md companion; full rows in JSON.
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False))
    summary = {k: report[k] for k in report if k != "rows"}
    (OUT / "false-reject-soak-summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))
    del api_key
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0 if report.get("passed") else 2


if __name__ == "__main__":
    raise SystemExit(main())
