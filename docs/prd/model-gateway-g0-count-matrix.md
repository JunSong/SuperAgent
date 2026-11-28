# G0 count matrix · plan A vs C (configured console keys)

| Item | Value |
| --- | --- |
| Date | 2026-11-27 (matrix probe); **updated 2026-11-28** |
| Policy intent | **A** (vendor count) primary; **C** (`bytes/4`) soft fallback |
| Scope | All **enabled** `provider_credential` rows for company `demo` |
| Script | `scripts/g0-count-tokens-matrix.py` |
| Raw | `docs/prd/g0-evidence/count-matrix/report.json` |

## Console inventory (2026-11-27 probe)

Only three providers had keys in admin at matrix time:

| Provider | Credential status | Plan A (2026-11-27 probe) | Bill probe | 2026-11-28 update |
| --- | --- | --- | --- | --- |
| **deepseek** | ok | **usable** — Anthropic `count_tokens` (flash sample 295=295) | ok | Production-shaped dsh count-vs-bill + **false-reject soak passed**; `hardEligible=deepseek` |
| **grok** | ok | matrix paths 404 | ok | **`/v1/tokenize-text` usable** (token_ids); soft LOCAL_ESTIMATE + framing; not hardEligible |
| **minimax** | error (empty base in DB) | none found | ok on default host | Still LOCAL stand-in / no vendor count |

Not in console (no key to probe): claude, codex, glm, kimi, qwen, codebuddy, gemini, qoder.

## DeepSeek detail

- **Matrix sample (flash, synthetic):** count 295 = bill 295; heuristic 84 under-shoots — **not** the hardEligible basis.
- **Hard basis (2026-11-28):** `deepseek-chat` dsh-shaped body + soak — see `model-gateway-g0-deepseek-evidence.md` and `g0-evidence/deepseek/false-reject-soak.md`.

## Product reading of “A primary, C fallback”

| Mode | Behavior |
| --- | --- |
| Soft / managed | Prefer A (or local) per catalogue; on failure **warn + C**. Never claim PROVEN on C. |
| Hard (`hardQuotaClis`) | Prefer A; on failure **refuse** (no C). PROVEN only if provider count succeeded. DeepSeek may be listed after soak. |

Code: `model-gateway-input-counter-strategy.md`.

## Next

1. When more keys are added in admin, re-run `python3 scripts/g0-count-tokens-matrix.py`.
2. Grok framing calibrate vs bill before any hardEligible expansion.
3. MiniMax real BPE when available.
