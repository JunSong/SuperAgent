package com.superagent.gateway.count;

/**
 * Context for a pre-upstream input count.
 *
 * @param cli            runner key ({@code deepseek}, {@code grok}, …)
 * @param protocol       gateway protocol segment ({@code anthropic} / {@code openai})
 * @param upstreamBase   resolved vendor base (DeepSeek already Anthropic-normalized)
 * @param apiKey         company pool key
 * @param bearer         whether to send {@code Authorization: Bearer}
 * @param body           outbound JSON body (after output-cap clamp)
 * @param hardRequired   {@code true} when this CLI is on {@code hardQuotaClis}
 */
public record InputCountRequest(
        String cli,
        String protocol,
        String upstreamBase,
        String apiKey,
        boolean bearer,
        byte[] body,
        boolean hardRequired) {
}
