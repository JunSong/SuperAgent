# I3 evidence · xAI `/v1/tokenize-text`

| Item | Value |
| --- | --- |
| Date | 2026-11-28 |
| Script | `scripts/g0-grok-tokenize-probe.py` |
| Raw | `tokenize-text-report.json` |
| Credential | demo `grok` (`key_sha256_12=3dd74b7bc030`) |

## Result

**Usable.** `POST https://api.x.ai/v1/tokenize-text` returns 200 for `grok-4-1-fast-non-reasoning` and `grok-3-mini`.

| Check | Result |
| --- | --- |
| Endpoint exists | yes (not 404) |
| Response shape | `{"token_ids":[{token_id,string_token,token_bytes},…]}` |
| Token count | `token_ids.length` (no scalar `token_count`) |
| `grok-2-latest` | 404 model not found / no access |
| Chat bill smoke | `prompt_tokens=189` on tiny completion |

Tiny rendered text `"Human: Reply with exactly: ping\n\n"` → **8** tokens. Same user message billed at **189** prompt tokens → large chat/system framing. Soft path therefore keeps **LOCAL_ESTIMATE** (`token_ids.len + framing pad`), not PROVIDER / hardEligible.

## Gateway follow-up (this build)

- Parse `token_ids` in `XaiTokenizeText`
- Catalogue `grok` → `PROVIDER_XAI_TOKENIZE` again (soft); fail → warn+C
- `hardEligible` remains false until framing calibrated vs bill
