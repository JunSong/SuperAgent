#!/usr/bin/env python3
"""G0 DeepSeek (dsh) evidence probe.

Decrypts the company deepseek key in-process (never writes plaintext), records
CLI→upstream path via a local mock (full body kept for the primary turn), then:

1. Heuristic (bytes/4) vs live Messages ``usage.input_tokens`` on small samples
2. LiteLLM-style **count-vs-bill** on those samples **and** the captured
   production-shaped dsh body (``deepseek-chat`` + tools + system reminders):
   ``POST …/messages/count_tokens`` then ``POST …/messages`` (tiny max_tokens)

Outputs JSON + redacted artifacts under docs/prd/g0-evidence/deepseek/.
"""

from __future__ import annotations

import base64
import json
import os
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "--user", "cryptography", "-q"])
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/prd/g0-evidence/deepseek"
SECRET_KEY_FILE = ROOT / "superagent-server/data/secret.key"
DSH = ROOT / "superagent-server/data/runtime/node/current/bin/dsh"
MYSQL = ["mysql", "-h127.0.0.1", "-P3306", "-uroot", "-N", "-B", "-e"]


def mysql(sql: str) -> str:
    return subprocess.check_output(MYSQL + [sql], text=True)


def load_master_key() -> bytes:
    raw = SECRET_KEY_FILE.read_text().strip()
    key = base64.b64decode(raw)
    if len(key) != 32:
        raise SystemExit(f"secret.key must be 32 bytes, got {len(key)}")
    return key


def open_gcm(key: bytes, sealed_b64: str, aad: bytes | None) -> bytes:
    blob = base64.b64decode(sealed_b64)
    aes = AESGCM(key)
    return aes.decrypt(blob[:12], blob[12:], aad)


def decrypt_stored(master: bytes, stored: str, company_id: str | None, company_data_key_cipher: str | None) -> str:
    if stored.startswith("v2:"):
        rest = stored[len("v2:") :]
        company, sealed = rest.split(":", 1)
        if company_id and company != company_id:
            raise SystemExit(f"cipher company {company} != {company_id}")
        if not company_data_key_cipher or not company_data_key_cipher.startswith("v1:"):
            raise SystemExit("missing company data_key_cipher")
        data_key_b64 = open_gcm(master, company_data_key_cipher[len("v1:") :], f"company:{company}".encode()).decode()
        data_key = base64.b64decode(data_key_b64)
        return open_gcm(data_key, sealed, company.encode()).decode()
    if stored.startswith("v1:"):
        return open_gcm(master, stored[len("v1:") :], None).decode()
    raise SystemExit("unrecognised cipher prefix")


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Recorder:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.requests: list[dict] = []
        # Full bodies for live count-vs-bill (not written into mock-requests.json).
        self.raw_bodies: list[tuple[str, bytes]] = []

    def add(self, item: dict, raw: bytes | None = None) -> None:
        with self.lock:
            self.requests.append(item)
            if raw is not None:
                self.raw_bodies.append((item.get("path") or "", raw))


def make_handler(rec: Recorder):
    class H(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # noqa: A003
            return

        def do_POST(self):  # noqa: N802
            n = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(n)
            auth = self.headers.get("Authorization", "")
            auth_redacted = "Bearer ***" if auth.lower().startswith("bearer ") else ("***" if auth else "")
            headers = {k: ("***" if k.lower() in {"authorization", "x-api-key"} else v) for k, v in self.headers.items()}
            rec.add(
                {
                    "method": "POST",
                    "path": self.path,
                    "content_type": self.headers.get("Content-Type"),
                    "body_bytes": len(body),
                    "auth": auth_redacted,
                    "headers": headers,
                    "body_preview": body[:800].decode("utf-8", "replace"),
                    "body_sha256_prefix": __import__("hashlib").sha256(body).hexdigest()[:16],
                },
                raw=body if "/v1/messages" in (self.path or "") else None,
            )
            # Minimal Anthropic Messages SSE so dsh can finish a turn.
            events = (
                'event: message_start\ndata: {"type":"message_start","message":{"id":"msg_g0","type":"message","role":"assistant","content":[],"model":"deepseek-chat","usage":{"input_tokens":12,"output_tokens":0}}}\n\n'
                'event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}\n\n'
                'event: content_block_delta\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"g0-ok"}}\n\n'
                'event: content_block_stop\ndata: {"type":"content_block_stop","index":0}\n\n'
                'event: message_delta\ndata: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}\n\n'
                'event: message_stop\ndata: {"type":"message_stop"}\n\n'
            ).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(events)))
            self.end_headers()
            self.wfile.write(events)

        def do_GET(self):  # noqa: N802
            rec.add({"method": "GET", "path": self.path, "body_bytes": 0})
            body = b'{"data":[{"id":"deepseek-chat"}]}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return H


def heuristic_input(body: bytes) -> int:
    return max(1, (len(body) + 3) // 4)


def run_dsh_against(base_url: str, api_key: str, prompt: str, session_id: str | None, work: Path) -> dict:
    env = os.environ.copy()
    env["PATH"] = str(DSH.parent) + os.pathsep + env.get("PATH", "")
    env["DEEPSEEK_API_KEY"] = api_key
    env["DEEPSEEK_BASE_URL"] = base_url
    ws = work / "ws"
    ws.mkdir(parents=True, exist_ok=True)
    # Use the machine's DSH_HOME (same as SuperAgent). Shipped profile "headless" cannot take
    # --from-default-profile (dsh errors); do not invent a blank home that lacks plugins.
    patch = work / "patch.yml"
    patch.write_text(
        "- id: agent-default-model\n"
        "  name: '@deepseek-ai/dsh-agent-default-model'\n"
        "  config:\n"
        "    provider: deepseek-official\n"
        "    model: deepseek-chat\n"
    )
    cmd = [
        str(DSH),
        "--profile",
        "headless",
        "--patch",
        str(patch),
        "--json",
        "--permission-mode",
        "read-only",
        "--approval-policy",
        "never",
        "-",
    ]
    if session_id:
        i = cmd.index("--permission-mode")
        cmd[i:i] = ["--session-id", session_id]
    t0 = time.time()
    proc = subprocess.run(
        cmd,
        input=prompt,
        text=True,
        capture_output=True,
        env=env,
        cwd=str(ws),
        timeout=180,
    )
    elapsed = time.time() - t0
    lines = [ln for ln in (proc.stdout or "").splitlines() if ln.strip()]
    session = None
    usage_steps = []
    for ln in lines:
        if '"type":"session"' in ln or '"type": "session"' in ln:
            try:
                session = json.loads(ln).get("sessionId")
            except json.JSONDecodeError:
                pass
        if "step_end" in ln:
            try:
                u = json.loads(ln).get("usage") or {}
                usage_steps.append(u)
            except json.JSONDecodeError:
                pass
    return {
        "exit_code": proc.returncode,
        "elapsed_s": round(elapsed, 3),
        "stdout_lines": len(lines),
        "stderr_tail": (proc.stderr or "")[-2000:],
        "session_id": session,
        "usage_steps": usage_steps,
        "stdout_sample": lines[:30],
        "cmd": [c if c != api_key else "***" for c in cmd],
        "base_url": base_url,
    }


ANTHROPIC_BASE = "https://api.deepseek.com/anthropic"
COUNT_URL = ANTHROPIC_BASE + "/v1/messages/count_tokens"
MESSAGES_URL = ANTHROPIC_BASE + "/v1/messages"
STRIP_COUNT_FIELDS = (
    "stream",
    "max_tokens",
    "max_output_tokens",
    "max_completion_tokens",
    "temperature",
    "top_p",
    "top_k",
    "stop_sequences",
)


def curl_post_json(url: str, api_key: str, body: dict, timeout: int = 120) -> dict:
    """POST JSON with Anthropic-compatible auth. Prefer curl (system certs)."""
    data = json.dumps(body, ensure_ascii=False)
    curl = subprocess.run(
        [
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
            "-H",
            "anthropic-version: 2023-06-01",
            "-H",
            f"x-api-key: {api_key}",
            "--data-binary",
            "@-",
        ],
        input=data,
        text=True,
        capture_output=True,
    )
    if curl.returncode == 0:
        out = curl.stdout or ""
        if "__HTTP_STATUS__:" in out:
            body_text, _, status_line = out.rpartition("\n__HTTP_STATUS__:")
            status = int(status_line.strip() or "0")
        else:
            body_text, status = out, 0
        if 200 <= status < 300:
            try:
                return {"ok": True, "status": status, "body": json.loads(body_text)}
            except json.JSONDecodeError:
                return {"ok": False, "status": status, "body": body_text[:2000]}
        return {"ok": False, "status": status, "body": body_text[:2000]}

    ctx = ssl.create_default_context()
    try:
        import certifi  # type: ignore

        ctx.load_verify_locations(certifi.where())
    except Exception:
        ctx = ssl._create_unverified_context()
    req = urllib.request.Request(
        url,
        data=data.encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
            "anthropic-version": "2023-06-01",
            "x-api-key": api_key,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            raw = resp.read()
            return {"ok": True, "status": resp.status, "body": json.loads(raw.decode())}
    except urllib.error.HTTPError as e:
        return {"ok": False, "status": e.code, "body": e.read().decode("utf-8", "replace")[:2000]}
    except Exception as e:
        return {"ok": False, "status": 0, "body": f"curl_rc={curl.returncode} curl_err={curl.stderr!r} urllib={e!r}"}


def live_messages(api_key: str, body: dict) -> dict:
    return curl_post_json(MESSAGES_URL, api_key, body, timeout=120)


def strip_for_count(body: dict) -> dict:
    """Match Java AnthropicCountTokens.stripForCount."""
    out = dict(body)
    for f in STRIP_COUNT_FIELDS:
        out.pop(f, None)
    return out


def prepare_bill_body(body: dict, max_tokens: int = 8) -> dict:
    out = dict(body)
    out["stream"] = False
    out["max_tokens"] = max_tokens
    # Disable thinking if present to keep bill cheap / stable.
    if isinstance(out.get("thinking"), dict):
        out["thinking"] = {"type": "disabled"}
    return out


def count_vs_bill(api_key: str, name: str, body: dict) -> dict:
    """LiteLLM-style: same shaped body → count_tokens then minimal messages bill."""
    raw = json.dumps(body, ensure_ascii=False).encode()
    count_body = strip_for_count(body)
    bill_body = prepare_bill_body(body)
    t0 = time.time()
    counted = curl_post_json(COUNT_URL, api_key, count_body, timeout=120)
    t_count = round(time.time() - t0, 3)
    count_tokens = None
    if counted.get("ok") and isinstance(counted.get("body"), dict):
        n = counted["body"].get("input_tokens")
        if isinstance(n, int):
            count_tokens = n
    t1 = time.time()
    billed = live_messages(api_key, bill_body)
    t_bill = round(time.time() - t1, 3)
    usage = {}
    if billed.get("ok") and isinstance(billed.get("body"), dict):
        usage = billed["body"].get("usage") or {}
    bill_tokens = None
    parts = []
    for k in ("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"):
        if isinstance(usage.get(k), int):
            parts.append(usage[k])
    if parts:
        bill_tokens = sum(parts)
    heur = heuristic_input(raw)
    delta = None
    if isinstance(count_tokens, int) and isinstance(bill_tokens, int):
        delta = count_tokens - bill_tokens
    return {
        "name": name,
        "model": body.get("model"),
        "body_bytes": len(raw),
        "body_sha256_12": __import__("hashlib").sha256(raw).hexdigest()[:12],
        "has_tools": bool(body.get("tools")),
        "message_count": len(body.get("messages") or []) if isinstance(body.get("messages"), list) else None,
        "heuristic_input": heur,
        "count_url": COUNT_URL,
        "count_ok": bool(counted.get("ok")),
        "count_http_status": counted.get("status"),
        "count_input_tokens": count_tokens,
        "count_elapsed_s": t_count,
        "count_error_preview": None if counted.get("ok") else str(counted.get("body"))[:500],
        "bill_url": MESSAGES_URL,
        "bill_ok": bool(billed.get("ok")),
        "bill_http_status": billed.get("status"),
        "bill_input_tokens": bill_tokens,
        "bill_output_tokens": usage.get("output_tokens"),
        "bill_elapsed_s": t_bill,
        "bill_error_preview": None if billed.get("ok") else str(billed.get("body"))[:500],
        "usage_raw": usage,
        "count_minus_bill": delta,
        "count_ge_bill": (delta is not None and delta >= 0),
        "count_eq_bill": (delta == 0),
        "heuristic_under_bill": bool(isinstance(bill_tokens, int) and heur < bill_tokens),
        "aligned": bool(delta == 0),
    }


def pick_primary_dsh_body(raw_bodies: list[tuple[str, bytes]]) -> tuple[dict, bytes] | None:
    """Prefer the largest /v1/messages JSON that looks like a real agent turn (tools)."""
    candidates: list[tuple[int, dict, bytes]] = []
    for path, raw in raw_bodies:
        if "/v1/messages" not in path:
            continue
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
        if not isinstance(body, dict):
            continue
        score = len(raw)
        if body.get("tools"):
            score += 1_000_000
        candidates.append((score, body, raw))
    if not candidates:
        return None
    candidates.sort(key=lambda x: x[0], reverse=True)
    _, body, raw = candidates[0]
    return body, raw


def sample_bodies() -> list[tuple[str, dict]]:
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
            "tools_shaped",
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


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    master = load_master_key()
    company_id = "demo"
    dk = mysql(f"SELECT data_key_cipher FROM superagent.company WHERE id='{company_id}'").strip()
    row = mysql(
        "SELECT id, key_cipher, base_url FROM superagent.provider_credential "
        f"WHERE company_id='{company_id}' AND provider_key='deepseek' AND enabled=1 AND deleted=0 "
        "ORDER BY priority LIMIT 1"
    ).strip().split("\t")
    cred_id, key_cipher, base_url = row[0], row[1], row[2]
    api_key = decrypt_stored(master, key_cipher, company_id, dk)
    key_fp = __import__("hashlib").sha256(api_key.encode()).hexdigest()[:12]

    report: dict = {
        "cli": "dsh",
        "cli_version": subprocess.check_output([str(DSH), "--version"], text=True).strip(),
        "company_id": company_id,
        "credential_id": cred_id,
        "stored_base_url": base_url,
        "normalized_anthropic_base": "https://api.deepseek.com/anthropic",
        "key_sha256_12": key_fp,
        "key_len": len(api_key),
        "mock": {},
        "live_api": {},
        "warm_resume": {},
        "verdict": {},
    }

    # --- Mock path matrix via real dsh ---
    with tempfile.TemporaryDirectory(prefix="g0-dsh-") as tmp:
        work = Path(tmp)
        (work / "ws").mkdir()
        port = free_port()
        rec = Recorder()
        httpd = ThreadingHTTPServer(("127.0.0.1", port), make_handler(rec))
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        mock_base = f"http://127.0.0.1:{port}/anthropic"
        mock_run = run_dsh_against(mock_base, "sk-g0-mock-key", "Reply with exactly: g0-ok", None, work)
        # Give late requests a moment
        time.sleep(0.5)
        httpd.shutdown()
        primary = pick_primary_dsh_body(rec.raw_bodies)
        dsh_body_meta = None
        if primary is not None:
            dsh_body, dsh_raw = primary
            (OUT / "dsh-shaped-body.json").write_text(
                json.dumps(dsh_body, ensure_ascii=False, indent=2)
            )
            dsh_body_meta = {
                "path": "dsh-shaped-body.json",
                "body_bytes": len(dsh_raw),
                "body_sha256_12": __import__("hashlib").sha256(dsh_raw).hexdigest()[:12],
                "model": dsh_body.get("model"),
                "has_tools": bool(dsh_body.get("tools")),
                "message_count": len(dsh_body.get("messages") or [])
                if isinstance(dsh_body.get("messages"), list)
                else None,
                "captured_from": "mock dsh → local Anthropic stub (same process)",
            }
        report["mock"] = {
            "base_url": mock_base,
            "dsh": {k: v for k, v in mock_run.items() if k != "stdout_sample"}
            | {"stdout_sample": mock_run["stdout_sample"][:15]},
            "recorded_requests": rec.requests,
            "paths": sorted({r.get("path") for r in rec.requests}),
            "dsh_shaped_body": dsh_body_meta,
        }
        (OUT / "mock-requests.json").write_text(json.dumps(rec.requests, indent=2, ensure_ascii=False))
        (OUT / "mock-dsh-stdout.txt").write_text("\n".join(mock_run.get("stdout_sample") or []))
        (OUT / "mock-dsh-stderr.txt").write_text(mock_run.get("stderr_tail") or "")

        # Warm / resume: second turn with --session-id if first produced one
        sid = mock_run.get("session_id")
        if sid:
            # restart mock for resume attempt (same process isolation)
            port2 = free_port()
            rec2 = Recorder()
            httpd2 = ThreadingHTTPServer(("127.0.0.1", port2), make_handler(rec2))
            threading.Thread(target=httpd2.serve_forever, daemon=True).start()
            resume = run_dsh_against(
                f"http://127.0.0.1:{port2}/anthropic",
                "sk-g0-mock-key",
                "Say resume-ok",
                sid,
                work,
            )
            time.sleep(0.3)
            httpd2.shutdown()
            report["warm_resume"] = {
                "first_session_id": sid,
                "resume_exit_code": resume["exit_code"],
                "resume_session_id": resume.get("session_id"),
                "resume_stderr_tail": resume.get("stderr_tail"),
                "resume_paths": sorted({r.get("path") for r in rec2.requests}),
                "note": "Mock only proves CLI accepts --session-id and still hits upstream; fidelity vs vendor warm not claimed.",
            }
        else:
            report["warm_resume"] = {
                "first_session_id": None,
                "note": "dsh mock run did not emit session id; resume not tested",
                "stderr_tail": mock_run.get("stderr_tail"),
            }

    # Keep primary body for live count-vs-bill after the tempdir closes (already on disk).
    dsh_shaped_for_live = None
    shaped_path = OUT / "dsh-shaped-body.json"
    if shaped_path.is_file():
        dsh_shaped_for_live = json.loads(shaped_path.read_text())

    # --- Live Anthropic Messages usage vs heuristic (small samples) ---
    live_rows = []
    under_estimates = 0
    for name, body in sample_bodies():
        raw = json.dumps(body, ensure_ascii=False).encode()
        est_in = heuristic_input(raw)
        result = live_messages(api_key, body)
        usage = {}
        if result.get("ok"):
            usage = (result["body"] or {}).get("usage") or {}
        prompt_tokens = usage.get("input_tokens")
        if isinstance(prompt_tokens, int) and est_in < prompt_tokens:
            under_estimates += 1
        live_rows.append(
            {
                "name": name,
                "body_bytes": len(raw),
                "heuristic_input": est_in,
                "vendor_input_tokens": prompt_tokens,
                "vendor_output_tokens": usage.get("output_tokens"),
                "ratio_heur_over_vendor": (
                    round(est_in / prompt_tokens, 3)
                    if isinstance(prompt_tokens, int) and prompt_tokens
                    else None
                ),
                "under_estimate": bool(isinstance(prompt_tokens, int) and est_in < prompt_tokens),
                "http_ok": result.get("ok"),
                "http_status": result.get("status"),
                "error_preview": None if result.get("ok") else str(result.get("body"))[:500],
                "usage_raw": usage,
            }
        )

    # --- LiteLLM-style count-vs-bill (samples + production-shaped dsh body) ---
    cvb_rows = []
    for name, body in sample_bodies():
        cvb_rows.append(count_vs_bill(api_key, name, body))
    dsh_cvb = None
    if isinstance(dsh_shaped_for_live, dict):
        dsh_cvb = count_vs_bill(api_key, "dsh_shaped_primary", dsh_shaped_for_live)
        cvb_rows.append(dsh_cvb)
        (OUT / "dsh-body-count-vs-bill.json").write_text(
            json.dumps(dsh_cvb, indent=2, ensure_ascii=False)
        )
        # Keep legacy bill-only artifact name for older readers, now script-produced.
        (OUT / "dsh-body-live-compare.json").write_text(
            json.dumps(
                {
                    "http_status": dsh_cvb.get("bill_http_status"),
                    "ok": dsh_cvb.get("bill_ok"),
                    "usage": dsh_cvb.get("usage_raw"),
                    "vendor_input": dsh_cvb.get("bill_input_tokens"),
                    "body_bytes": dsh_cvb.get("body_bytes"),
                    "heuristic_input": dsh_cvb.get("heuristic_input"),
                    "under_estimate": dsh_cvb.get("heuristic_under_bill"),
                    "ratio": (
                        round(dsh_cvb["heuristic_input"] / dsh_cvb["bill_input_tokens"], 3)
                        if isinstance(dsh_cvb.get("bill_input_tokens"), int)
                        and dsh_cvb.get("bill_input_tokens")
                        else None
                    ),
                    "body_sha256_12": dsh_cvb.get("body_sha256_12"),
                    "model": dsh_cvb.get("model"),
                    "has_tools": dsh_cvb.get("has_tools"),
                    "message_count": dsh_cvb.get("message_count"),
                    "count_input_tokens": dsh_cvb.get("count_input_tokens"),
                    "count_minus_bill": dsh_cvb.get("count_minus_bill"),
                    "count_eq_bill": dsh_cvb.get("count_eq_bill"),
                    "produced_by": "scripts/g0-deepseek-probe.py count_vs_bill",
                },
                indent=2,
                ensure_ascii=False,
            )
        )

    report["live_api"] = {
        "endpoint": MESSAGES_URL,
        "samples": live_rows,
        "under_estimate_count": under_estimates,
        "sample_count": len(live_rows),
        "count_vs_bill": {
            "method": "LiteLLM-style: strip generation fields → count_tokens; same body + max_tokens=8 → messages",
            "count_url": COUNT_URL,
            "bill_url": MESSAGES_URL,
            "rows": cvb_rows,
            "dsh_shaped": dsh_cvb,
            "all_rows_count_ge_bill": all(r.get("count_ge_bill") for r in cvb_rows),
            "all_rows_count_eq_bill": all(r.get("count_eq_bill") for r in cvb_rows),
            "dsh_shaped_count_ge_bill": bool(dsh_cvb and dsh_cvb.get("count_ge_bill")),
            "dsh_shaped_count_eq_bill": bool(dsh_cvb and dsh_cvb.get("count_eq_bill")),
        },
    }

    # Verdict: align machine-readable report with archived soak + catalogue hardEligible.
    path_ok = any("/v1/messages" in (p or "") for p in report["mock"].get("paths") or [])
    live_ok = all(r.get("http_ok") for r in live_rows)
    all_ge = bool(cvb_rows) and all(r.get("count_ge_bill") for r in cvb_rows)
    dsh_ge = bool(dsh_cvb and dsh_cvb.get("count_ge_bill"))
    soak_summary_path = OUT / "false-reject-soak-summary.json"
    soak_passed = False
    soak_ref = None
    if soak_summary_path.is_file():
        try:
            soak_summary = json.loads(soak_summary_path.read_text())
            soak_passed = bool(soak_summary.get("passed"))
            soak_ref = {
                "path": "false-reject-soak-summary.json",
                "passed": soak_passed,
                "n_measured": soak_summary.get("n_measured"),
                "under_count_rate": soak_summary.get("under_count_rate"),
                "rel_5pct_false_reject_rate": soak_summary.get("rel_5pct_false_reject_rate"),
                "pass_line": soak_summary.get("pass_line"),
            }
        except (OSError, json.JSONDecodeError):
            soak_passed = False
    hard_proven = bool(dsh_ge and soak_passed)
    report["verdict"] = {
        "path_matrix_recorded": path_ok,
        "live_usage_readable": live_ok,
        "heuristic_never_under_estimates": under_estimates == 0 and live_ok,
        "count_vs_bill_all_count_ge_bill": all_ge,
        "count_vs_bill_dsh_shaped_count_ge_bill": dsh_ge,
        "count_vs_bill_dsh_shaped_exact": bool(dsh_cvb and dsh_cvb.get("count_eq_bill")),
        "false_reject_soak": soak_ref,
        "hard_input_bound_proven": hard_proven,
        "reason": (
            "I1 + soak archived: production-shaped deepseek-chat count≥bill; "
            "false-reject soak passed under the relative-5% pass line "
            "(see false-reject-soak.md §Pass line / v3 refinement). "
            "Catalogue may set hardEligible=deepseek; company must still list it on hardQuotaClis. "
            "Hard path refuses when vendor count fails (no heuristic C)."
            if hard_proven
            else (
                "I1/soak incomplete or soak summary missing/failed — do not treat hardEligible as proven. "
                "Re-run scripts/g0-deepseek-false-reject-soak.py and keep false-reject-soak-summary.json."
            )
        ),
        "next_for_PROVEN": (
            [
                "Ops: add deepseek to company hardQuotaClis when request-level hard R is desired.",
                "Follow-ups: Grok framing calibrate; MiniMax BPE; count audit/ledger (N2).",
            ]
            if hard_proven
            else [
                "Archive a passing false-reject soak (scripts/g0-deepseek-false-reject-soak.py).",
                "Only then keep hardEligible / allow deepseek on hardQuotaClis.",
            ]
        ),
    }

    (OUT / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
    # scrub key from process env leftovers
    del api_key
    summary = {
        "out": str(OUT / "report.json"),
        "dsh_shaped_count_vs_bill": dsh_cvb,
        "verdict": report["verdict"],
    }
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
