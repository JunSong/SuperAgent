# G0 count matrix · plan A vs C (configured console keys)

| Item | Value |
| --- | --- |
| Date | 2026-11-27 |
| Policy intent | **A** (vendor `count_tokens`) primary; **C** (`bytes/4`) soft fallback |
| Scope | All **enabled** `provider_credential` rows for company `demo` |
| Script | `scripts/g0-count-tokens-matrix.py` |
| Raw | `docs/prd/g0-evidence/count-matrix/report.json` |

## Console inventory

Only three providers have keys in admin (not the full CLI catalogue):

| Provider | Credential status | Plan A (`count_tokens`) | Bill probe | Notes |
| --- | --- | --- | --- | --- |
| **deepseek** | ok | **usable** — `POST …/anthropic/v1/messages/count_tokens` | ok | count **295** = billed input **295** (exact). Heuristic on same body **84** → under |
| **grok** | ok | **none found** (404 on tried paths) | ok (`prompt_tokens` 277) | Must stay on C until xAI exposes a count API or local tokenizer |
| **minimax** | error (empty base in DB) | **none found** | ok on default host | Inference works with guessed base; no count endpoint in probe set |

Not in console (no key to probe): claude, codex, glm, kimi, qwen, codebuddy, gemini, qoder.

## DeepSeek detail (A green)

- Count URL: `https://api.deepseek.com/anthropic/v1/messages/count_tokens` (~140 ms)
- Bill URL: `https://api.deepseek.com/anthropic/v1/messages` (same shaped body + `max_tokens`)
- Alignment: `count - bill = 0`, `count ≥ bill`
- Confirms earlier G0: **C under-shoots** on tool-shaped bodies; **A matches bill** on this sample

## Product reading of “A primary, C fallback”

| Mode | Behavior |
| --- | --- |
| Soft / managed | Prefer A (or local) per catalogue; on failure **warn + C**. Never claim PROVEN on C. |
| Hard (`hardQuotaClis`) | Prefer A; on failure **warn + C** (no silent PROVEN). Effective PROVEN only if provider count succeeded. |

Code path is live — see `model-gateway-input-counter-strategy.md`. This matrix remains the measurement archive for console keys.

## Next

1. When more keys are added in admin, re-run `python3 scripts/g0-count-tokens-matrix.py`.
2. Grok framing calibration / MiniMax real BPE before expanding `hardEligible`.
3. DeepSeek false-reject soak before product hard-spend wording.
