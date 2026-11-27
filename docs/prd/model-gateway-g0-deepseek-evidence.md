# G0 evidence · DeepSeek CLI (`dsh`)

| Item | Value |
| --- | --- |
| Date | 2026-11-27 |
| CLI | `dsh` **0.2.2-rc.5** (`@deepseek-ai/dsh`, SuperAgent runtime bin) |
| Company / credential | `demo` / console deepseek entry (key never written to this doc) |
| Probe script | `scripts/g0-deepseek-probe.py` |
| Raw artifacts | `docs/prd/g0-evidence/deepseek/` (`report.json`, mock captures, live compare) |
| Verdict | **Hard input bound NOT proven.** Soft/heuristic path stays; do not enable `hardQuotaClis` for deepseek. |

## What was authorized and run

User authorized live DeepSeek API use with the key already configured in SuperAgent admin. Probe:

1. Decrypts the company key in-process (AES-GCM via `secret.key` + company data key); never prints or stores plaintext.
2. Runs real `dsh --profile headless` against a local mock Anthropic server (`DEEPSEEK_BASE_URL=http://127.0.0.1:<port>/anthropic`).
3. Calls `https://api.deepseek.com/anthropic/v1/messages` for usage truth samples.
4. Replays a **real dsh-shaped** request body (tools + system reminders, ~120 KB) live once for bound comparison.

## Path / protocol matrix (CLI → upstream)

| Observation | Evidence |
| --- | --- |
| Env wiring (code) | `DeepSeekCliAgentRunner`: `DEEPSEEK_API_KEY`, optional `DEEPSEEK_BASE_URL` |
| Stored console base | `https://api.deepseek.com/chat/completions` → normalized to `https://api.deepseek.com/anthropic` |
| Actual mock hit | `POST /anthropic/v1/messages` |
| Auth header | `x-api-key` (not `Authorization: Bearer`) |
| Stream | `Accept: text/event-stream`, body `"stream": true` |
| Extra headers | `anthropic-version: 2023-06-01`, `x-deepseek-harness-user-id`, `x-deepseek-harness-session-id` |
| User-Agent | `deepseek-harness/0.2.2-rc.5 … dsh-profile/headless` |
| Body shape | Anthropic Messages JSON; large tool list + `<system-reminder>` blobs; secondary title-generation call |

Gateway implication: anthropic protocol adapter + `/v1/messages` is the correct DeepSeek harness path when base ends with `/anthropic`.

## Usage fields (vendor + CLI)

### Live Anthropic Messages `usage` (non-stream samples)

```json
{
  "input_tokens": 9,
  "cache_creation_input_tokens": 0,
  "cache_read_input_tokens": 0,
  "output_tokens": 1,
  "service_tier": "standard"
}
```

(dsh-shaped live call also returned `output_tokens_details.thinking_tokens`.)

### CLI JSONL (`step_end`) — already parsed in runtime

`DeepSeekCliAgentRunner.addStepUsage`: `inputTokens`, `cacheReadTokens`, `cacheWriteTokens`, `outputTokens`, `reasoningTokens`.

## Soft heuristic (`body_bytes/4`) vs vendor `input_tokens`

| Sample | Body bytes | Heuristic input | Vendor `input_tokens` | Heur ≥ vendor? |
| --- | --- | --- | --- | --- |
| short | 115 | 29 | 9 | yes |
| medium | 1370 | 343 | 204 | yes |
| tools_shaped (tiny) | ~280 | 70 | 139–267 | **no (under)** |
| real dsh body (tools+reminders) | ~119678 | 29920 | 10238 | yes (~2.9× over) |

Conclusion: **bytes/4 is not a safe hard upper bound**. Compact tool-heavy requests under-reserve; large harness bodies over-reserve. Suitable only for option C soft / HEURISTIC labeling.

## Warm / `--session-id`

| Check | Result |
| --- | --- |
| First mock run emits `sessionId` | yes |
| Second run with `--session-id` exit 0 | yes |
| Still hits `/anthropic/v1/messages` | yes |
| Vendor warm fidelity / env hot-swap under gateway token | **not claimed** (mock only) |

## G0 exit vs hard R

| Gate | Status |
| --- | --- |
| Protocol × CLI path recorded | **pass** (deepseek / anthropic / `/v1/messages`) |
| Stream usage field map (vendor Messages) | **pass** (see above) |
| Proven tokenizer / count API | **pass** (sample: count=bill=295; wired `AnthropicCountTokens`; fail → warn+C) |
| False-reject baseline ≤ agreed line | **open** (soak not archived; heuristic still under-shoots tools) |
| Enable `hardQuotaClis` / `HARD_INPUT_BOUND_AVAILABLE` | **code open** (`hardEligible=deepseek`); product hard-spend claim still gated on soak |

## Next for product PROVEN claim

1. Two-phase false-reject study on real dsh bodies (stub capture → offline estimate vs live `input_tokens`).
2. Archive agreed false-reject line; then treat DeepSeek on `hardQuotaClis` as a product guarantee, not just a code path.

Until soak is archived: monthly B/F yes; request-level hard vendor spend guarantee no (even if DeepSeek is listed).
