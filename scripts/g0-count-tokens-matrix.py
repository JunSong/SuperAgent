#!/usr/bin/env python3
"""Probe vendor count_tokens (plan A) for every enabled console credential.

Policy intent under test: A primary, C (bytes/4) fallback — this script only measures
whether each vendor exposes a usable pre-request count API and how it compares to billed usage.
Never prints API keys.
"""

from __future__ import annotations

import base64
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
OUT = ROOT / "docs/prd/g0-evidence/count-matrix"
SECRET_KEY_FILE = ROOT / "superagent-server/data/secret.key"
MYSQL = ["mysql", "-h127.0.0.1", "-P3306", "-uroot", "-N", "-B", "-e"]

# Candidate count + bill endpoints per provider family. Paths are absolute on the host origin.
PROBES = {
    "deepseek": {
        "model": "deepseek-flash",
        "origins": lambda base: [root(base) or "https://api.deepseek.com"],
        "count_paths": [
            "/anthropic/v1/messages/count_tokens",
            "/v1/messages/count_tokens",
        ],
        "bill_paths": [
            ("anthropic_messages", "/anthropic/v1/messages", "anthropic"),
            ("openai_chat_v1", "/v1/chat/completions", "openai"),
        ],
        "auth": "both",  # x-api-key + bearer
    },
    "grok": {
        "model": "grok-4-1-fast-non-reasoning",
        "origins": lambda base: [normalize_openai_root(base) or "https://api.x.ai/v1"],
        "count_paths": [
            "/messages/count_tokens",
            "/chat/completions/count_tokens",
            "/tokenize/count_tokens",  # speculative xAI paths
        ],
        "bill_paths": [
            ("openai_chat", "/chat/completions", "openai"),
        ],
        "auth": "bearer",
        "alt_models": [
            "grok-4.20-0309-non-reasoning",
            "grok-3-mini",
            "grok-2-latest",
        ],
    },
    "minimax": {
        "model": "MiniMax-M2",
        "origins": lambda base: [
            b
            for b in [
                (base or "").strip().rstrip("/") or None,
                "https://api.minimax.chat/v1",
                "https://api.minimaxi.com/v1",
                "https://api.minimax.io/anthropic",
                "https://api.minimax.io/v1",
            ]
            if b
        ],
        "count_paths": [
            "/v1/messages/count_tokens",
            "/messages/count_tokens",
            "/anthropic/v1/messages/count_tokens",
            "/chat/completions/count_tokens",
        ],
        "bill_paths": [
            ("openai_chat", "/chat/completions", "openai"),
            ("anthropic_messages", "/v1/messages", "anthropic"),
        ],
        "auth": "bearer",
        "alt_models": ["MiniMax-M2.1", "MiniMax-M3.1-Flash", "MiniMax-Text-01"],
    },
}


def mysql(sql: str) -> str:
    return subprocess.check_output(MYSQL + [sql], text=True)


def root(base: str) -> str:
    b = (base or "").strip().rstrip("/")
    for suf in ("/anthropic", "/chat/completions", "/v1"):
        if b.endswith(suf):
            b = b[: -len(suf)]
    return b


def normalize_openai_root(base: str) -> str:
    b = (base or "").strip().rstrip("/")
    return b


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


def curl_json(url: str, headers: dict[str, str], body: dict | None, timeout: int = 45) -> dict:
    cmd = ["curl", "-sS", "-m", str(timeout), "-w", "\n__HTTP_STATUS__:%{http_code}", "-X", "POST" if body is not None else "GET", url]
    for k, v in headers.items():
        cmd += ["-H", f"{k}: {v}"]
    data = None
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "--data-binary", "@-"]
        data = json.dumps(body)
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
        "url": url,
        "status": status,
        "ok": 200 <= status < 300,
        "elapsed_s": elapsed,
        "curl_rc": p.returncode,
        "curl_err": (p.stderr or "")[:300],
        "body_preview": text[:800],
        "json": parsed,
    }


def auth_headers(api_key: str, mode: str) -> dict[str, str]:
    h = {"anthropic-version": "2023-06-01"}
    if mode in ("bearer", "both"):
        h["Authorization"] = f"Bearer {api_key}"
    if mode in ("x-api-key", "both"):
        h["x-api-key"] = api_key
    return h


def anthropic_count_body(model: str) -> dict:
    return {
        "model": model,
        "messages": [{"role": "user", "content": "Reply with exactly: ping"}],
        "system": "You are a concise assistant.",
        "tools": [
            {
                "name": "echo",
                "description": "Echo text",
                "input_schema": {
                    "type": "object",
                    "properties": {"text": {"type": "string"}},
                    "required": ["text"],
                },
            }
        ],
    }


def anthropic_bill_body(model: str) -> dict:
    b = anthropic_count_body(model)
    b["max_tokens"] = 16
    b["stream"] = False
    return b


def openai_bill_body(model: str) -> dict:
    return {
        "model": model,
        "max_tokens": 16,
        "messages": [
            {"role": "system", "content": "You are a concise assistant."},
            {"role": "user", "content": "Reply with exactly: ping"},
        ],
        "tools": [
            {
                "type": "function",
                "function": {
                    "name": "echo",
                    "description": "Echo text",
                    "parameters": {
                        "type": "object",
                        "properties": {"text": {"type": "string"}},
                        "required": ["text"],
                    },
                },
            }
        ],
    }


def join(origin: str, path: str) -> str:
    o = origin.rstrip("/")
    if not path:
        return o
    if path.startswith("/"):
        # If origin already ends with /v1 and path starts with /v1, avoid doubling
        if o.endswith("/v1") and path.startswith("/v1/"):
            return o + path[3:]
        # If origin is .../v1 and path is /chat/completions → .../v1/chat/completions
        return o + path
    return o + "/" + path


def extract_count(parsed) -> int | None:
    if not isinstance(parsed, dict):
        return None
    for k in ("input_tokens", "inputTokens", "total_tokens"):
        if isinstance(parsed.get(k), int):
            return parsed[k]
    usage = parsed.get("usage")
    if isinstance(usage, dict):
        for k in ("input_tokens", "prompt_tokens", "total_tokens"):
            if isinstance(usage.get(k), int):
                return usage[k]
    return None


def extract_bill_input(parsed, style: str) -> int | None:
    if not isinstance(parsed, dict):
        return None
    usage = parsed.get("usage") or {}
    if style == "anthropic":
        inp = usage.get("input_tokens")
        cache_r = usage.get("cache_read_input_tokens") or 0
        cache_c = usage.get("cache_creation_input_tokens") or 0
        if isinstance(inp, int):
            return inp + (cache_r if isinstance(cache_r, int) else 0) + (cache_c if isinstance(cache_c, int) else 0)
    if style.startswith("openai"):
        pt = usage.get("prompt_tokens")
        if isinstance(pt, int):
            return pt
    return extract_count(parsed)


def heuristic(body: dict) -> int:
    raw = json.dumps(body, ensure_ascii=False).encode()
    return max(1, (len(raw) + 3) // 4)


def probe_provider(name: str, api_key: str, stored_base: str, status: str) -> dict:
    cfg = PROBES[name]
    origins = []
    for o in cfg["origins"](stored_base):
        if o and o not in origins:
            origins.append(o)
    result = {
        "provider": name,
        "credential_status": status,
        "stored_base": stored_base,
        "key_sha256_12": hashlib.sha256(api_key.encode()).hexdigest()[:12],
        "origins_tried": origins,
        "count_attempts": [],
        "bill_attempts": [],
        "best_count": None,
        "best_bill": None,
        "alignment": None,
        "plan_A_usable": False,
        "fallback_C_note": "bytes/4 heuristic remains available as soft fallback",
    }
    headers = auth_headers(api_key, cfg["auth"])

    # Count + bill across model candidates (cheap max_tokens=16 on bill)
    models = [cfg["model"], *cfg.get("alt_models", [])]
    for model in models:
        count_body = anthropic_count_body(model)
        for origin in origins:
            for path in cfg["count_paths"]:
                url = join(origin, path)
                if "/v1/v1/" in url:
                    continue
                if any(a.get("url") == url and a.get("model") == model for a in result["count_attempts"]):
                    continue
                resp = curl_json(url, headers, count_body)
                entry = {
                    "model": model,
                    "url": url,
                    "status": resp["status"],
                    "ok": resp["ok"],
                    "elapsed_s": resp["elapsed_s"],
                    "input_tokens": extract_count(resp["json"]) if resp["ok"] else None,
                    "error_preview": None if resp["ok"] else resp["body_preview"][:400],
                }
                result["count_attempts"].append(entry)
                if entry["ok"] and entry["input_tokens"] is not None and result["best_count"] is None:
                    result["best_count"] = entry
        for origin in origins:
            for label, path, style in cfg["bill_paths"]:
                if style == "anthropic":
                    body = anthropic_bill_body(model)
                else:
                    body = openai_bill_body(model)
                url = join(origin, path)
                resp = curl_json(url, headers, body)
                entry = {
                    "model": model,
                    "label": label,
                    "url": url,
                    "status": resp["status"],
                    "ok": resp["ok"],
                    "elapsed_s": resp["elapsed_s"],
                    "billed_input": extract_bill_input(resp["json"], style) if resp["ok"] else None,
                    "usage": (resp["json"] or {}).get("usage") if resp["ok"] and isinstance(resp["json"], dict) else None,
                    "error_preview": None if resp["ok"] else resp["body_preview"][:400],
                    "heuristic_on_bill_body": heuristic(body),
                }
                result["bill_attempts"].append(entry)
                if entry["ok"] and entry["billed_input"] is not None:
                    result["best_bill"] = entry
                    break
            if result["best_bill"]:
                break
        if result["best_bill"] and result["best_count"]:
            break
        if result["best_bill"] and not result["best_count"]:
            # keep trying models for count, but bill already ok
            pass

    if result["best_count"] and result["best_bill"]:
        c = result["best_count"]["input_tokens"]
        b = result["best_bill"]["billed_input"]
        result["alignment"] = {
            "count": c,
            "billed_input": b,
            "count_minus_bill": c - b,
            "count_ge_bill": c >= b,
            "heuristic": result["best_bill"]["heuristic_on_bill_body"],
            "heuristic_ge_bill": result["best_bill"]["heuristic_on_bill_body"] >= b,
        }
        # Usable for plan A only if count works and is a safe upper bound (or equal)
        result["plan_A_usable"] = bool(c >= b)
    elif result["best_count"] and not result["best_bill"]:
        result["alignment"] = {"note": "count OK but bill probe failed — cannot align yet"}
        result["plan_A_usable"] = False
    else:
        result["plan_A_usable"] = False

    return result


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    master = load_master()
    company = "demo"
    dk = mysql(f"SELECT data_key_cipher FROM superagent.company WHERE id='{company}'").strip()
    rows = mysql(
        "SELECT provider_key, id, key_cipher, base_url, status FROM superagent.provider_credential "
        f"WHERE company_id='{company}' AND enabled=1 AND deleted=0 ORDER BY provider_key, priority"
    ).strip().splitlines()

    # one credential per provider (first by priority)
    seen = set()
    report = {
        "policy": "A (vendor count) primary, C (bytes/4) fallback — measurement only; code not switched",
        "company_id": company,
        "providers": [],
        "summary": {},
    }
    for line in rows:
        parts = line.split("\t")
        if len(parts) < 5:
            continue
        provider, cred_id, cipher, base, status = parts[0], parts[1], parts[2], parts[3], parts[4]
        if provider in seen or provider not in PROBES:
            continue
        seen.add(provider)
        key = decrypt(master, cipher, company, dk)
        try:
            result = probe_provider(provider, key, base, status)
        finally:
            del key
        result["credential_id"] = cred_id
        report["providers"].append(result)

    report["summary"] = {
        "configured_enabled": sorted(seen),
        "plan_A_usable": [p["provider"] for p in report["providers"] if p.get("plan_A_usable")],
        "count_endpoint_found": [
            p["provider"] for p in report["providers"] if p.get("best_count")
        ],
        "bill_ok": [p["provider"] for p in report["providers"] if p.get("best_bill")],
        "must_use_C_only_for_now": [
            p["provider"] for p in report["providers"] if not p.get("plan_A_usable")
        ],
        "not_in_console": [
            k
            for k in (
                "claude",
                "codex",
                "glm",
                "kimi",
                "qwen",
                "codebuddy",
                "gemini",
                "qoder",
            )
            if k not in seen
        ],
    }
    (OUT / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print(json.dumps(report["summary"], indent=2))
    for p in report["providers"]:
        print(
            p["provider"],
            "A_usable=",
            p["plan_A_usable"],
            "count=",
            (p.get("best_count") or {}).get("url"),
            "bill=",
            (p.get("best_bill") or {}).get("billed_input"),
            "align=",
            p.get("alignment"),
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
