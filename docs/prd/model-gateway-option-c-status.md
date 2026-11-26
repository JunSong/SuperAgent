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

## Deferred (not done in this change)

These remain open and must not be described as closed:

1. **G0-proven tokenizer / count API** for any CLI — no local tokenizer, no vendor count integration, no fingerprint/drift handling.
2. **False-reject baseline** (mis-reject rate denominator) required before turning on hard R for a combination.
3. **Per-CLI `PROVEN` path** in `ReservationEstimator` — enum exists; implementation always returns `HEURISTIC`.
4. **Enabling `hardQuotaClis` in production** — blocked in `GatewayPolicyService` until (1)+(2) land.
5. **Earlier backlog still open**: first-event/idle timeouts, run-token sweep, `bytes/4` UTF-8 under-estimate fix (only via proven bound), QUOTA_BUSY wait condition, log minimization, employee `REPLACE INTO`, RECON mid-month auto top-up job, full MySQL concurrency soak, real vendor/CLI E2E.

## Product wording

- Safe to say: employees with B exhausted cannot start a new managed turn; output caps are forced; overage can hit `RECON_RESERVE` / `PENDING_RECONCILIATION`.
- **Not** safe to say: every managed request’s pre-reserve R is a hard guarantee against vendor overspend.

When a CLI finishes G0 evidence, flip `HARD_INPUT_BOUND_AVAILABLE`, implement the proven estimator, then allow that CLI on `hardQuotaClis`.
