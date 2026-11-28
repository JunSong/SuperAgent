# DeepSeek false-reject soak (hardEligible gate)

| Item | Value |
| --- | --- |
| Date | 2026-11-28 |
| Script | `scripts/g0-deepseek-false-reject-soak.py` |
| Corpus | `soak-corpus/` (112 bodies: dsh seed + mutations + tool strata + chat) |
| Full rows | `false-reject-soak.json` |
| Summary | `false-reject-soak-summary.json` |

## Pass line (design review v3)

| Gate | Result |
| --- | --- |
| N ≥ 100 measured | **112** |
| under_count_rate (C &lt; B) | **0** |
| rel_false_reject_rate (C &gt; B×1.05) | **0** |
| count_fail | **0** |
| **passed** | **true** → recommend `hardEligible=deepseek` |

## Method

1. Local corpus (no employee traffic): production-shaped `deepseek-chat` bodies from captured dsh harness + controlled mutations.
2. Offline: `count_tokens` → C; minimal `messages` → B.
3. **B includes cache**: `input_tokens + cache_read_input_tokens + cache_creation_input_tokens` (DeepSeek often parks tool schemas in cache_read; using uncached-only understates bill and falsely inflates FR).

## Notes

- Exact-fit FR (C&gt;B) is high (~84%) because count systematically overshoots by ~25 tokens on tool-heavy bodies; relative 5% line is the product gate.
- Hard path in gateway: when CLI is on `hardQuotaClis`, count failure **refuses** (no heuristic C fallback).
