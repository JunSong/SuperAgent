package com.superagent.core.gateway;

/**
 * LiteLLM-aligned counting strategy for a CLI (how to obtain a pre-request input token number).
 */
public enum InputCounterKind {
    /** Anthropic Messages {@code POST …/v1/messages/count_tokens}. */
    PROVIDER_ANTHROPIC_COUNT,
    /** xAI {@code POST …/tokenize-text} after chat-template render (+ framing). */
    PROVIDER_XAI_TOKENIZE,
    /** OpenAI Responses {@code …/input_tokens} (and kin). */
    PROVIDER_OPENAI_INPUT_TOKENS,
    /** Google / Gemini {@code :countTokens}. */
    PROVIDER_GOOGLE_COUNT,
    /** Local BPE/HF/tiktoken when no vendor count exists. */
    LOCAL_TOKENIZER,
    /** {@code body bytes/4} soft fallback. */
    HEURISTIC
}
