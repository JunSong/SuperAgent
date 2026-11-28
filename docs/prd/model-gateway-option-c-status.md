# Model gateway · option C status (hard input bound)

| Item | Value |
| --- | --- |
| Branch intent | `#34` option C → proven hard input for DeepSeek after soak |
| Code HEAD note | Soft A/C for all; **DeepSeek `hardEligible=true`** after archived soak; hard count fail refuses |
| Date | 2026-11-28 |

## What this build does

- **Monthly B/F admission**: unchanged.
- **Output caps**: protocol-bound clamp of `max_*` fields.
- **Input half of R**: LiteLLM-aligned counters. Soft: prefer vendor/local, fail → warn+C. Hard (`hardQuotaClis`): DeepSeek may be listed; PROVEN only on PROVIDER count; **count fail → Forbidden** (no C).
- **Policy**: `hardQuotaClis` may include catalogue hard-eligible CLIs (**deepseek**).
- **Count gating**: grant recheck + quota precheck before count; count under whole-request budget.
- **Google count**: configured https base only; no silent host rewrite.
- **Grok**: `/tokenize-text` → LOCAL_ESTIMATE (+framing); not hardEligible.

## Evidence

- I1 / I3 / soak: see `model-gateway-g0-deepseek-evidence.md`, `g0-evidence/deepseek/false-reject-soak.md`, `g0-evidence/grok/`.

## Still open

- Grok framing calibrate vs bill; MiniMax real BPE; expand hardEligible to other CLIs after their soaks.
- MySQL concurrency soak, G3/G4 acceptance archive.

## Product wording

- Safe: DeepSeek on `hardQuotaClis` uses vendor count for R; count failure blocks the turn; monthly B/F still applies.
- Still not a blanket “never overspend vendor” for CLIs without hardEligible / without listing on `hardQuotaClis`.
