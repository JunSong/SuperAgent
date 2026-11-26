# Model gateway · option C status (hard input bound)

| Item | Value |
| --- | --- |
| Branch intent | `#34` option C: monthly B/F without tokenizer; request-level hard R only after G0 proof |
| Code HEAD note | Soft/heuristic input bound for all CLIs; `hardQuotaClis` rejected until a proven estimator exists |
| Date | 2026-11-26 |

## What this build does

- **Monthly B/F admission**: unchanged — reserve / settle / float / RECON still gate managed runs.
- **Output caps**: protocol-bound clamp of all known `max_*` fields (hard outbound ceiling for output).
- **Input half of R**: `GatewayInputBoundMode.HEURISTIC` (`body bytes/4`). Labelled in policy API as `inputBoundMode=HEURISTIC`, `hardInputBoundAvailable=false`.
- **Policy**: `hardQuotaClis` defaults to `[]`; non-empty updates are **rejected** until `GatewayPolicy.HARD_INPUT_BOUND_AVAILABLE` is true.
- **Admin UI**: copy states managed ≠ hard input bound; soft path is explicit.
- **Bounded upstream timeouts**: connect / first-byte / idle / **whole-request** (covers trickle streams) via `superagent.gateway.*-timeout-seconds`.
- **Run-token sweep**: expired / revoked in-memory grants removed on an interval.
- **QUOTA_BUSY wait**: waits when `available < R` but `available + reserved >= R` (not only `available == 0`).
- **Account upsert**: `putEmployee` / `putPool` no longer `REPLACE INTO` (preserves in-flight `reserved_tokens`).
- **RECON mid-month top-up**: scheduled job moves UNALLOCATED → RECON_RESERVE up to company target (never mints).
- **Usage identity**: `UsageRecord.runId` separate from chat `sessionId` (session rotation + settlement shadow); shadow looks up settlements by runId outside the report window (batched IN queries — no silent 500-id drop).
- **markPending**: locked + status-guarded so it cannot overwrite a concurrent SETTLED A.

## Deferred (not done in this change)

These remain open and must not be described as closed:

1. **G0-proven tokenizer / count API** for any CLI — no local tokenizer, no vendor count integration, no fingerprint/drift handling.
2. **False-reject baseline** (mis-reject rate denominator) required before turning on hard R for a combination.
3. **Per-CLI `PROVEN` path** in `ReservationEstimator` — enum exists; implementation always returns `HEURISTIC`.
4. **Enabling `hardQuotaClis` in production** — blocked in `GatewayPolicyService` until (1)+(2) land.
5. **Still open later**: full MySQL concurrency soak, real vendor/CLI E2E, G3/G4 acceptance archive, pending-alert / company-takeover SLA automation beyond RECON top-up.

## Product wording

- Safe to say: employees with B exhausted cannot start a new managed turn; output caps are forced; overage can hit `RECON_RESERVE` / `PENDING_RECONCILIATION`.
- **Not** safe to say: every managed request’s pre-reserve R is a hard guarantee against vendor overspend.

When a CLI finishes G0 evidence, flip `HARD_INPUT_BOUND_AVAILABLE`, implement the proven estimator, then allow that CLI on `hardQuotaClis`.
