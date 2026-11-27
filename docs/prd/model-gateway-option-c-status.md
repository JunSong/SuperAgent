# Model gateway · option C status (hard input bound)

| Item | Value |
| --- | --- |
| Branch intent | `#34` option C: monthly B/F without tokenizer; request-level hard R only after G0 proof |
| Code HEAD note | Soft path for all CLIs; DeepSeek may be listed on `hardQuotaClis` (`HARD_INPUT_BOUND_AVAILABLE=true`); count failure always **warn + C** |
| Date | 2026-11-28 |

## What this build does

- **Monthly B/F admission**: unchanged — reserve / settle / float / RECON still gate managed runs.
- **Output caps**: protocol-bound clamp of all known `max_*` fields (hard outbound ceiling for output).
- **Input half of R**: LiteLLM-aligned counters (see `model-gateway-input-counter-strategy.md`). Prefer vendor/local count; on failure **warn + `bytes/4`**. Effective `inputBoundMode=PROVEN` only when `InputCountSource.PROVIDER` succeeded.
- **Policy**: `hardQuotaClis` may include catalogue `hardEligible` CLIs only (currently **`deepseek`**). Non-eligible names are rejected.
- **Admin UI**: copy states managed ≠ hard input bound; soft path is explicit.
- **Bounded upstream timeouts**: connect / first-byte / idle / **whole-request** (covers trickle streams) via `superagent.gateway.*-timeout-seconds`.
- **Run-token sweep**: expired / revoked in-memory grants removed on an interval.
- **QUOTA_BUSY wait**: waits when `available < R` but `available + reserved >= R` (not only `available == 0`).
- **Account upsert**: `putEmployee` / `putPool` no longer `REPLACE INTO` (preserves in-flight `reserved_tokens`).
- **RECON mid-month top-up**: scheduled job moves UNALLOCATED → RECON_RESERVE up to company target (never mints).
- **Usage identity**: `UsageRecord.runId` separate from chat `sessionId` (session rotation + settlement shadow); shadow looks up settlements by runId outside the report window (batched IN queries — no silent 500-id drop).
- **markPending**: locked + status-guarded so it cannot overwrite a concurrent SETTLED A.

## Counter wiring (this build)

1. Soft and hard both **warn + fall back to C** on count failure; PROVEN only when provider count succeeds.
2. Wired: DeepSeek/Claude/GLM Anthropic count; Grok tokenize-text; Codex OpenAI input_tokens; Gemini countTokens; MiniMax local stand-in. Only DeepSeek is `hardEligible`.
3. Still open: Grok framing calibration, MiniMax real BPE, false-reject soak, hardEligible expansion after alignment.
4. Still open later: full MySQL concurrency soak, real vendor/CLI E2E, G3/G4 acceptance archive, pending-alert / company-takeover SLA automation beyond RECON top-up.

## Product wording

- Safe to say: employees with B exhausted cannot start a new managed turn; output caps are forced; overage can hit `RECON_RESERVE` / `PENDING_RECONCILIATION`; DeepSeek can prefer vendor count for R when listed on `hardQuotaClis`.
- **Not** safe to say: every managed request’s pre-reserve R is a hard guarantee against vendor overspend (count can fail → C; false-reject soak not archived).
