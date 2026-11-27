# Input token counter strategy (LiteLLM-aligned)

| Item | Value |
| --- | --- |
| Intent | Per-CLI input estimate for reservation R: **vendor count (A) when available**, else **local/heuristic (C)** |
| Soft managed | Try A / local → on failure **warn + C** (`bytes/4`) |
| Hard (`hardQuotaClis`) | Prefer A; on failure **warn + C** (no silent claim of PROVEN). Effective mode is PROVEN only when provider count actually returned |
| Catalogue | Every `ProviderCatalog` key |

## Matrix (all catalogue CLIs)

| CLI | Kind | Implementation (this build) | `hardEligible` | Notes |
| --- | --- | --- | --- | --- |
| `deepseek` | PROVIDER_ANTHROPIC_COUNT | `AnthropicCountTokens` → `{base}/v1/messages/count_tokens` | **yes** | G0 aligned |
| `claude` | PROVIDER_ANTHROPIC_COUNT | same | no | Wire works; align before hard |
| `glm` | PROVIDER_ANTHROPIC_COUNT | same | no | Zhipu Anthropic-compatible |
| `grok` | PROVIDER_XAI_TOKENIZE | `XaiTokenizeText` → `{root}/tokenize-text` + coarse template + framing pad | no | Framing pad conservative; calibrate later |
| `codex` | PROVIDER_OPENAI_INPUT_TOKENS | `OpenAiInputTokens` → `{root}/responses/input_tokens` | no | |
| `gemini` | PROVIDER_GOOGLE_COUNT | `GoogleCountTokens` → `models/{m}:countTokens` | no | Uses Google AI Studio root when credential base is unrelated |
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
