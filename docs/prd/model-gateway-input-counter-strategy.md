# Input token counter strategy (LiteLLM-aligned)

| Item | Value |
| --- | --- |
| Intent | Per-CLI input estimate for reservation R: **vendor count (A) when available**, else **local/heuristic (C)** |
| Soft managed | Try A / local → on failure **warn + C** (`bytes/4`) |
| Hard (`hardQuotaClis`) | DeepSeek eligible after soak. Prefer A; **fail → refuse** (no C). PROVEN only if PROVIDER succeeded |
| Catalogue | Every `ProviderCatalog` key |
| Gating | Grant recheck + quota precheck **before** count; count uses remaining whole-request timeout |

## Matrix (all catalogue CLIs)

| CLI | Kind | Implementation (this build) | `hardEligible` | Notes |
| --- | --- | --- | --- | --- |
| `deepseek` | PROVIDER_ANTHROPIC_COUNT | `AnthropicCountTokens` → `{base}/v1/messages/count_tokens` | **yes** | Soak 2026-11-28 passed (N=112, under=0, rel_FR=0) |
| `claude` | PROVIDER_ANTHROPIC_COUNT | same | no | Wire works; align before hard |
| `glm` | PROVIDER_ANTHROPIC_COUNT | same | no | Zhipu Anthropic-compatible |
| `grok` | PROVIDER_XAI_TOKENIZE | `tokenize-text` → `token_ids.len` + framing → **LOCAL_ESTIMATE** | no | I3: endpoint usable; framing uncalibrated (8 vs bill 189 on tiny) |
| `codex` | PROVIDER_OPENAI_INPUT_TOKENS | `OpenAiInputTokens` → `{root}/responses/input_tokens` | no | |
| `gemini` | PROVIDER_GOOGLE_COUNT | `GoogleCountTokens` on **configured** https base | no | No silent rewrite to Google public host; includes system/tools when present |
| `minimax` | LOCAL_TOKENIZER | stand-in = bytes/4 labeled `LOCAL_ESTIMATE` | no | Bundle MiniMax BPE later |
| `kimi` | HEURISTIC | C | no | |
| `qwen` | HEURISTIC | C | no | |
| `codebuddy` | HEURISTIC | C | no | |
| `qoder` | HEURISTIC | C | no | |

## Code map

- Catalogue: `com.superagent.core.gateway.InputCounterCatalog`
- Counters: `com.superagent.gateway.count.*`
- Wire-in: `UpstreamProxy` (Key → count → reserve); PROVEN only if `InputCountSource.PROVIDER`

## Failure policy

```text
try provider/local per catalogue
  success → use that number (PROVIDER or LOCAL_ESTIMATE)
  fail / unimplemented → log warn → HEURISTIC (bytes/4)
hardQuotaClis only changes preference + whether PROVEN is allowed when PROVIDER succeeds
```

## Wire contracts (LiteLLM-style, no live keys)

`CountWireContractTest` pins outbound shapes against local stubs:

- Anthropic: strip generation fields; keep tools/messages; fixture `{"input_tokens":N}`
- OpenAI Responses: chat → `input_text`/`output_text` parts (never chat `type:text`); fixture `{"object":"response.input_tokens","input_tokens":N}`
- Google: systemInstruction/tools/toolConfig on configured base; fixture `{"totalTokens":N}`
