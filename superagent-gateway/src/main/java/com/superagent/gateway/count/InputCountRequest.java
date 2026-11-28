package com.superagent.gateway.count;

import java.time.Duration;

/**
 * Context for a pre-upstream input count.
 *
 * @param cli            runner key ({@code deepseek}, {@code grok}, …)
 * @param protocol       gateway protocol segment ({@code anthropic} / {@code openai})
 * @param upstreamBase   resolved vendor base (DeepSeek already Anthropic-normalized)
 * @param apiKey         company pool key
 * @param bearer         whether to send {@code Authorization: Bearer}
 * @param body           outbound JSON body (after output-cap clamp)
 * @param hardPreferred  {@code true} when this CLI is on {@code hardQuotaClis} (log preference only)
 * @param timeout        remaining whole-request budget for the count call
 */
public record InputCountRequest(
        String cli,
        String protocol,
        String upstreamBase,
        String apiKey,
        boolean bearer,
        byte[] body,
        boolean hardPreferred,
        Duration timeout) {

    public InputCountRequest {
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    /** Backward-compatible ctor used by unit tests (default 30s timeout). */
    public InputCountRequest(
            String cli,
            String protocol,
            String upstreamBase,
            String apiKey,
            boolean bearer,
            byte[] body,
            boolean hardPreferred) {
        this(cli, protocol, upstreamBase, apiKey, bearer, body, hardPreferred, Duration.ofSeconds(30));
    }
}
