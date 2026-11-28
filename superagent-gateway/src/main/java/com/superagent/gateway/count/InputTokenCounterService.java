package com.superagent.gateway.count;

import com.superagent.core.gateway.InputCounterCatalog;
import com.superagent.core.gateway.InputCounterKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * LiteLLM-aligned input counter: try vendor/local count when the catalogue says so;
 * on failure or missing implementation, {@code warn} and fall back to {@code body bytes/4}
 * (including when the CLI is on {@code hardQuotaClis}).
 */
@Component
public class InputTokenCounterService {

    private static final Logger log = LoggerFactory.getLogger(InputTokenCounterService.class);

    private final HttpClient http;

    public InputTokenCounterService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public InputTokenCounterService(HttpClient http) {
        this.http = http;
    }

    /**
     * Test helper: never calls vendor count (avoids doubling stub hits / racing timeout fixtures).
     * Production Spring wiring uses the normal constructor.
     */
    public static InputTokenCounterService alwaysHeuristic() {
        return new InputTokenCounterService(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build()) {
            @Override
            public InputCountResult count(InputCountRequest req) {
                long n = Math.max(1L, ((req.body() == null ? 0 : req.body().length) + 3L) / 4L);
                return InputCountResult.heuristic(n);
            }
        };
    }

    public InputCountResult count(InputCountRequest req) {
        long heuristic = Math.max(1L, ((req.body() == null ? 0 : req.body().length) + 3L) / 4L);
        InputCounterCatalog.Strategy strategy = InputCounterCatalog.require(req.cli());
        try {
            InputCountResult provider = tryProvider(strategy.kind(), req);
            if (provider != null) {
                return provider;
            }
            if (req.hardPreferred()) {
                log.warn("input count unimplemented for hard-preferred cli={} kind={} → heuristic",
                        req.cli(), strategy.kind());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("input count interrupted cli={} kind={} → heuristic", req.cli(), strategy.kind());
        } catch (Exception e) {
            String msg = e.toString();
            if (looksLikeAuthFailure(msg)) {
                log.warn("input count auth/credential failure cli={} kind={} hardPreferred={} → heuristic: {}",
                        req.cli(), strategy.kind(), req.hardPreferred(), msg);
            } else {
                log.warn("input count provider failed cli={} kind={} hardPreferred={} → heuristic: {}",
                        req.cli(), strategy.kind(), req.hardPreferred(), msg);
            }
        }
        return InputCountResult.heuristic(heuristic);
    }

    private InputCountResult tryProvider(InputCounterKind kind, InputCountRequest req) throws Exception {
        Duration timeout = req.timeout();
        return switch (kind) {
            case PROVIDER_ANTHROPIC_COUNT -> AnthropicCountTokens.count(
                    http, req.upstreamBase(), req.apiKey(), req.bearer(), req.body(), timeout);
            case PROVIDER_XAI_TOKENIZE -> XaiTokenizeText.count(
                    http, req.upstreamBase(), req.apiKey(), req.body(), timeout);
            case PROVIDER_OPENAI_INPUT_TOKENS -> OpenAiInputTokens.count(
                    http, req.upstreamBase(), req.apiKey(), req.body(), timeout);
            case PROVIDER_GOOGLE_COUNT -> GoogleCountTokens.count(
                    http, req.upstreamBase(), req.apiKey(), req.body(), timeout);
            case LOCAL_TOKENIZER -> {
                // MiniMax (and kin): official BPE not bundled yet — label honestly, same math as C.
                long n = Math.max(1L, ((req.body() == null ? 0 : req.body().length) + 3L) / 4L);
                yield InputCountResult.localEstimate(n, "local tokenizer not bundled; bytes/4 stand-in");
            }
            case HEURISTIC -> null;
        };
    }

    private static boolean looksLikeAuthFailure(String msg) {
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase();
        return m.contains("http 401") || m.contains("http 403")
                || m.contains("unauthorized") || m.contains("forbidden")
                || m.contains("invalid api key") || m.contains("authentication");
    }
}
