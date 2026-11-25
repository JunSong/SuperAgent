package com.superagent.platform.provider;

import com.superagent.core.chat.RunCredential;

import java.net.http.HttpRequest;
import java.util.Optional;

/**
 * The Anthropic endpoint + key an HTTP call (model list, inference check) uses: a pool credential when one is
 * given, else the server's environment — the same order the runs themselves follow. Empty when there is no key
 * at all (a subscription login, which only the CLI can use).
 */
record ApiAccess(String base, String key, boolean bearer) {

    static final String ANTHROPIC = "https://api.anthropic.com";

    static Optional<ApiAccess> anthropic(RunCredential credential) {
        if (credential != null) {
            return Optional.of(new ApiAccess(trim(credential.baseUrl(), ANTHROPIC), credential.apiKey(), credential.bearer()));
        }
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String token = System.getenv("ANTHROPIC_AUTH_TOKEN");
        if (blank(apiKey) && blank(token)) {
            return Optional.empty();
        }
        return Optional.of(new ApiAccess(trim(System.getenv("ANTHROPIC_BASE_URL"), ANTHROPIC),
                blank(apiKey) ? token : apiKey, blank(apiKey)));
    }

    /** A vendor reached through a pool credential; its endpoint falls back to the catalogue's default. */
    static Optional<ApiAccess> of(com.superagent.core.provider.ProviderCatalog.Entry entry, RunCredential credential) {
        if (credential == null) {
            return Optional.empty();
        }
        String base = trim(credential.baseUrl(), entry.access().defaultBaseUrl());
        if ("deepseek".equals(entry.key())) {
            // People often paste the OpenAI chat URL; DeepSeek's harness speaks Anthropic beside /models on the root.
            base = normalizeDeepseekAnthropicBase(base);
        }
        return Optional.of(new ApiAccess(base, credential.apiKey(), credential.bearer()));
    }

    /** Where this endpoint lists models: its OpenAI-style API, which the Anthropic-compatible vendors keep beside it. */
    String modelsUrl(String providerKey) {
        return switch (providerKey) {
            case "deepseek" -> deepseekApiRoot(base) + "/models";
            case "glm" -> base.replaceFirst("/api/anthropic$", "/api/paas/v4") + "/models";
            default -> base + "/models";
        };
    }

    /**
     * DeepSeek's OpenAI root ({@code https://api.deepseek.com}). Strips {@code /anthropic}, {@code /v1}, and
     * {@code /chat/completions} that people paste as the "endpoint".
     */
    static String deepseekApiRoot(String base) {
        String b = blank(base) ? "https://api.deepseek.com" : base.strip().replaceAll("/+$", "");
        b = b.replaceFirst("/anthropic$", "");
        b = b.replaceFirst("/chat/completions$", "");
        b = b.replaceFirst("/v1$", "");
        return b;
    }

    /** Anthropic-compatible base the DeepSeek harness uses ({@code …/anthropic}). */
    static String normalizeDeepseekAnthropicBase(String base) {
        return deepseekApiRoot(base) + "/anthropic";
    }

    boolean viaGateway() {
        return !ANTHROPIC.equals(base);
    }

    HttpRequest.Builder authorize(HttpRequest.Builder req) {
        return bearer ? req.header("Authorization", "Bearer " + key) : req.header("x-api-key", key);
    }

    /** OpenAI-style auth, whatever this entry's own header choice. */
    HttpRequest.Builder authorizeBearer(HttpRequest.Builder req) {
        return req.header("Authorization", "Bearer " + key);
    }

    private static String trim(String url, String fallback) {
        return blank(url) ? fallback : url.strip().replaceAll("/+$", "");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
