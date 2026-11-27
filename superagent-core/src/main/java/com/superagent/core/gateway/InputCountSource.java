package com.superagent.core.gateway;

/**
 * Where the input half of reservation R came from (LiteLLM {@code litellm_count_source} analogue).
 */
public enum InputCountSource {
    /** Vendor HTTP count / tokenize API. */
    PROVIDER,
    /** Local tokenizer / framing estimate (not wired for most CLIs yet). */
    LOCAL_ESTIMATE,
    /** Soft {@code body bytes/4} fallback. */
    HEURISTIC
}
