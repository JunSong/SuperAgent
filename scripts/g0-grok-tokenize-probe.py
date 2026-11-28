#!/usr/bin/env python3
"""I3 evidence: is xAI POST /v1/tokenize-text usable with the company grok key?

Decrypts the demo grok credential in-process (never prints the key). Tries the
paths our gateway / LiteLLM would use, then optionally compares token length to
a tiny chat completion prompt_tokens (bill framing is separate).

Writes docs/prd/g0-evidence/grok/tokenize-text-report.json
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
OUT = ROOT / "docs/prd/g0-evidence/grok"
SECRET_KEY_FILE = ROOT / "superagent-server/data/secret.key"
MYSQL = ["mysql", "-h127.0.0.1", "-P3306", "-uroot", "-N", "-B", "-e"]

MODELS = [
    "grok-4-1-fast-non-reasoning",
    "grok-3-mini",
    "grok-2-latest",
]

PATHS = [
    "/tokenize-text",
    "/v1/tokenize-text",  # in case stored base is https://api.x.ai without /v1
]


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


def curl_json(url: str, api_key: str, body: dict, timeout: int = 45) -> dict:
    data = json.dumps(body)
    cmd = [
        "curl",
        "-sS",
        "-m",
        str(timeout),
        "-w",
        "\n__HTTP_STATUS__:%{http_code}",
        "-X",
        "POST",
        url,
        "-H",
        "Content-Type: application/json",
        "-H",
        f"Authorization: Bearer {api_key}",
        "--data-binary",
        "@-",
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
        "url": url,
        "status": status,
        "ok": 200 <= status < 300,
        "elapsed_s": elapsed,
        "curl_rc": p.returncode,
        "curl_err": (p.stderr or "")[:300],
        "body_preview": text[:1200],
        "json": parsed,
    }


def extract_token_len(parsed) -> tuple[int | None, str | None]:
    if not isinstance(parsed, dict):
        return None, None
    for k in ("token_count", "tokenCount", "count", "input_tokens"):
        if isinstance(parsed.get(k), int):
            return parsed[k], k
    for k in ("token_ids", "tokens"):
        v = parsed.get(k)
        if isinstance(v, list):
            return len(v), f"{k}.len"
    usage = parsed.get("usage")
    if isinstance(usage, dict):
        for k in ("input_tokens", "prompt_tokens", "total_tokens"):
            if isinstance(usage.get(k), int):
                return usage[k], f"usage.{k}"
    return None, None


def join(base: str, path: str) -> str:
    b = base.rstrip("/")
    if path.startswith("/v1/") and b.endswith("/v1"):
        return b + path[3:]
    if not path.startswith("/"):
        return b + "/" + path
    return b + path


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    master = load_master()
    company = "demo"
    dk = mysql(f"SELECT data_key_cipher FROM superagent.company WHERE id='{company}'").strip()
    row = mysql(
        "SELECT id, key_cipher, base_url FROM superagent.provider_credential "
        f"WHERE company_id='{company}' AND provider_key='grok' AND enabled=1 AND deleted=0 "
        "ORDER BY priority LIMIT 1"
    ).strip().split("\t")
    cred_id, key_cipher, base_url = row[0], row[1], row[2]
    api_key = decrypt(master, key_cipher, company, dk)
    key_fp = hashlib.sha256(api_key.encode()).hexdigest()[:12]
    base = (base_url or "https://api.x.ai/v1").strip().rstrip("/")

    text = "Human: Reply with exactly: ping\n\n"
    chat_body = {
        "model": MODELS[0],
        "messages": [{"role": "user", "content": "Reply with exactly: ping"}],
        "max_tokens": 8,
    }

    attempts = []
    for model in MODELS:
        for path in PATHS:
            url = join(base, path)
            # Skip obvious double-/v1 when base already has /v1 and path is /v1/...
            if base.endswith("/v1") and path.startswith("/v1/"):
                continue
            body = {"text": text, "model": model}
            r = curl_json(url, api_key, body)
            n, how = extract_token_len(r.get("json"))
            attempts.append(
                {
                    "model": model,
                    "url": url,
                    "status": r["status"],
                    "ok": r["ok"],
                    "elapsed_s": r["elapsed_s"],
                    "token_len": n,
                    "token_len_from": how,
                    "response_keys": sorted(r["json"].keys()) if isinstance(r.get("json"), dict) else None,
                    "error_preview": None if r["ok"] else r["body_preview"][:500],
                    "ok_preview": r["body_preview"][:500] if r["ok"] else None,
                }
            )

    # Also try without model (some docs show text-only)
    url = join(base, "/tokenize-text")
    r = curl_json(url, api_key, {"text": text})
    n, how = extract_token_len(r.get("json"))
    attempts.append(
        {
            "model": None,
            "url": url,
            "status": r["status"],
            "ok": r["ok"],
            "elapsed_s": r["elapsed_s"],
            "token_len": n,
            "token_len_from": how,
            "response_keys": sorted(r["json"].keys()) if isinstance(r.get("json"), dict) else None,
            "error_preview": None if r["ok"] else r["body_preview"][:500],
            "ok_preview": r["body_preview"][:500] if r["ok"] else None,
            "note": "text-only body (no model field)",
        }
    )

    usable = [a for a in attempts if a["ok"] and isinstance(a.get("token_len"), int) and a["token_len"] > 0]
    best = usable[0] if usable else None

    bill = curl_json(join(base, "/chat/completions"), api_key, chat_body)
    bill_tokens = None
    if bill.get("ok") and isinstance(bill.get("json"), dict):
        usage = bill["json"].get("usage") or {}
        if isinstance(usage.get("prompt_tokens"), int):
            bill_tokens = usage["prompt_tokens"]

    report = {
        "date": time.strftime("%Y-%m-%d"),
        "company_id": company,
        "credential_id": cred_id,
        "stored_base": base_url,
        "key_sha256_12": key_fp,
        "endpoint_under_test": "POST {base}/tokenize-text",
        "attempts": attempts,
        "usable": bool(best),
        "best": best,
        "bill_probe": {
            "url": join(base, "/chat/completions"),
            "ok": bill.get("ok"),
            "status": bill.get("status"),
            "prompt_tokens": bill_tokens,
            "error_preview": None if bill.get("ok") else (bill.get("body_preview") or "")[:500],
        },
        "verdict": {
            "tokenize_text_usable": bool(best),
            "reason": (
                f"HTTP {best['status']} on {best['url']}; token_len={best['token_len']} via {best['token_len_from']}"
                if best
                else "No successful tokenize-text response with a parseable token length"
            ),
            "framing_calibrated": False,
            "note": (
                "Response uses token_ids[] (length = token count), not a scalar token_count. "
                "Gateway XaiTokenizeText must read token_ids. Soft A may use this; hardEligible still off."
                if best
                else "Keep grok on HEURISTIC until an alternate count path is found."
            ),
        },
    }
    out = OUT / "tokenize-text-report.json"
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False))
    del api_key
    print(json.dumps({"out": str(out), "verdict": report["verdict"], "best": best}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
