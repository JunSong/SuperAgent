# G0 evidence · DeepSeek CLI (`dsh`)

| Item | Value |
| --- | --- |
| Date | 2026-11-28 |
| CLI | `dsh` **0.2.2-rc.5** |
| Probe | `scripts/g0-deepseek-probe.py` |
| Soak | `scripts/g0-deepseek-false-reject-soak.py` |
| Verdict | **Hard input bound proven** (`report.json` + soak summary aligned). Pass line = relative-5% FR (v3 refinement). Enable via company `hardQuotaClis`. |

## I1 · count-vs-bill (production-shaped)

| Sample | Count C | Bill B (incl. cache) | Δ |
| --- | --- | --- | --- |
| dsh_shaped (~119KB, tools) | 10240 | 10215 | +25 |

Bill total = `input_tokens + cache_read_input_tokens + cache_creation_input_tokens`.

## False-reject soak (hardEligible gate)

See `g0-evidence/deepseek/false-reject-soak.md`.

| Gate | Result |
| --- | --- |
| N | 112 |
| under_count_rate | 0 |
| rel_FR (C &gt; B×1.05) | 0 |
| passed | **true** |

## Hard path behaviour

- Company may list `deepseek` on `hardQuotaClis` → `inputBoundMode=PROVEN` when provider count succeeds.
- Count failure on a hard CLI **refuses** the request (no heuristic C fallback).

## Soft path

Other CLIs / DeepSeek when not on `hardQuotaClis`: prefer vendor count, warn+C on failure.
