# Decision: DeepSeek hardEligible soak pass line (relative 5%)

| Field | Value |
| --- | --- |
| Date | 2026-11-28 |
| Status | **Approved** |
| Scope | G0 DeepSeek Anthropic `count_tokens` → `hardEligible` / company `hardQuotaClis` |
| Related | `model-gateway-token-quota-review-v3.md` P1-A; R11 review P1 option 1; R12 I-1 |
| Evidence | `docs/prd/g0-evidence/deepseek/false-reject-soak.md` + `false-reject-soak-summary.json` |

## Decision

Adopt **relative-5% false-reject** as the DeepSeek hardEligible soak pass line:

- Keep v3: **N ≥ 100**, two-phase corpus + offline replay (P2-1), stratified counts, **under_count (C &lt; B) = 0**.
- Refine v3’s “总误拒率 ≤5%” for this vendor: pass when **share of samples with C &gt; B × 1.05 ≤ 5%**.
- Report **exact-fit** rate (C &gt; B) for transparency; it is **not** the pass gate.

## Why (exact-fit ~84% is not material FR)

DeepSeek Anthropic `count_tokens` systematically overshoots billed input by a small absolute margin (~+25 tokens on tool-heavy production-shaped bodies). Reserving C when C = B+ε with ε ≪ 5% of B is soft-safe over-reserve, not a product false reject. Treating every C &gt; B as FR would ban any conservative count API and contradict the soft-safe intent of v3’s ≤5% line. Relative 5% operationalizes “material mis-reject under tight budgets.”

## Approver

- **Role**: product / G0 owner (feat/model-gateway)
- **Date**: 2026-11-28
- **Record**: this file is the independent decision log (not embedded only inside the soak evidence narrative). R11 P1 option 1 required an approval artifact linked from the soak doc; R12 I-1 closed by landing this file and syncing v3 §1 P1-A.

## Sync targets

- `docs/prd/model-gateway-token-quota-review-v3.md` §1 P1-A row → note DeepSeek relative-5% refinement + link here
- `docs/prd/g0-evidence/deepseek/false-reject-soak.md` → link here as approval record
