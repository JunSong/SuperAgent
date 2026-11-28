# DeepSeek false-reject soak (hardEligible gate)

| Item | Value |
| --- | --- |
| Date | 2026-11-28 |
| Script | `scripts/g0-deepseek-false-reject-soak.py` |
| Corpus | `soak-corpus/` (112 bodies: dsh seed + mutations + tool strata + chat; gitignored, rebuild with `--corpus-only`) |
| Full rows | `false-reject-soak.json` |
| Summary | `false-reject-soak-summary.json` |

## Pass line (v3 refinement — design approval on this branch)

`model-gateway-token-quota-review-v3.md` P1-A required: **N≥100**, two-phase stub/offline measurement (P2-1), **总误拒率 ≤5%**, stratified counts.

This soak **keeps** v3’s N、两阶段、分层计数. It **refines** “总误拒率” for a vendor count that systematically overshoots bill by a few tokens (~25 on tool-heavy dsh bodies):

| Metric | Definition | Role |
| --- | --- | --- |
| `under_count` | C &lt; B | **Hard fail** if any (would allow overspend) |
| `exact_fit_false_reject` | C &gt; B with remaining A:=B | Diagnostic only (84% here — expected from +~25 overshoot) |
| `rel_5pct_false_reject` | C &gt; B × 1.05 | **Pass line ≤5%** — material over-reserve that would false-reject under tight budgets |

**Why exact-fit is not the pass line:** reserving C when C = B+ε (ε≪5% of B) is soft-safe over-reserve, not a product false reject. Treating every C>B as FR would ban any conservative count API. The v3 “≤5%” intent is material mis-reject rate; relative 5% overshoot operationalizes that for DeepSeek Anthropic count.

**Approval record (this branch):** product/G0 decision 2026-11-28 — adopt relative-5% as the DeepSeek hardEligible soak line; archive exact-fit rate for transparency. See also R11 review P1 option 1.

### Result

| Gate | Result |
| --- | --- |
| N ≥ 100 measured | **112** |
| under_count_rate (C &lt; B) | **0** |
| rel_false_reject_rate (C &gt; B×1.05) | **0** |
| exact_fit_false_reject_rate (C &gt; B) | 0.839 (reported, not pass gate) |
| count_fail | **0** |
| **passed** | **true** → `hardEligible=deepseek` |

## Method

1. Local corpus (no employee traffic): production-shaped `deepseek-chat` bodies from captured dsh harness + controlled mutations.
2. Offline: `count_tokens` → C; minimal `messages` → B.
3. **B includes cache**: `input_tokens + cache_read_input_tokens + cache_creation_input_tokens`.

## Hard path behaviour

When CLI is on `hardQuotaClis`, count failure **refuses** the request (no heuristic C fallback).
