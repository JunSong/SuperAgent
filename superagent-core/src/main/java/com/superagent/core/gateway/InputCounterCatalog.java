package com.superagent.core.gateway;

import com.superagent.core.provider.ProviderCatalog;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-CLI input-count strategy (LiteLLM-aligned). {@link #hardEligible()} is gated by G0 alignment
 * evidence in this build — not merely by having an HTTP count endpoint.
 */
public final class InputCounterCatalog {

    /**
     * @param kind           LiteLLM-style counter family
     * @param hardEligible   may be listed on {@link GatewayPolicy#hardQuotaClis()}
     * @param notes          short operator hint
     */
    public record Strategy(InputCounterKind kind, boolean hardEligible, String notes) {
    }

    private static final Map<String, Strategy> BY_CLI = Map.ofEntries(
            entry("deepseek", InputCounterKind.PROVIDER_ANTHROPIC_COUNT, true,
                    "Wired: Anthropic count_tokens; warn+C on failure; hardEligible"),
            entry("claude", InputCounterKind.PROVIDER_ANTHROPIC_COUNT, false,
                    "Wired: Anthropic count_tokens; warn+C; hard after alignment"),
            entry("glm", InputCounterKind.PROVIDER_ANTHROPIC_COUNT, false,
                    "Wired: Zhipu Anthropic count_tokens; warn+C"),
            entry("grok", InputCounterKind.PROVIDER_XAI_TOKENIZE, false,
                    "Wired: /tokenize-text + template + framing pad; warn+C"),
            entry("codex", InputCounterKind.PROVIDER_OPENAI_INPUT_TOKENS, false,
                    "Wired: /responses/input_tokens; warn+C"),
            entry("kimi", InputCounterKind.HEURISTIC, false,
                    "Heuristic (no vendor count)"),
            entry("qwen", InputCounterKind.HEURISTIC, false,
                    "Heuristic (no vendor count)"),
            entry("minimax", InputCounterKind.LOCAL_TOKENIZER, false,
                    "LOCAL_ESTIMATE stand-in (bytes/4) until MiniMax BPE bundled"),
            entry("codebuddy", InputCounterKind.HEURISTIC, false,
                    "Heuristic"),
            entry("gemini", InputCounterKind.PROVIDER_GOOGLE_COUNT, false,
                    "Wired: models/{m}:countTokens; warn+C"),
            entry("qoder", InputCounterKind.HEURISTIC, false,
                    "Heuristic"));

    private InputCounterCatalog() {
    }

    public static Optional<Strategy> find(String cli) {
        if (cli == null || cli.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CLI.get(cli.strip().toLowerCase(Locale.ROOT)));
    }

    public static Strategy require(String cli) {
        return find(cli).orElseGet(() -> new Strategy(InputCounterKind.HEURISTIC, false, "unknown CLI"));
    }

    /** CLIs that may appear on {@code hardQuotaClis} in this build. */
    public static Set<String> hardEligibleClis() {
        return BY_CLI.entrySet().stream()
                .filter(e -> e.getValue().hardEligible())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static boolean hardInputBoundAvailable() {
        return !hardEligibleClis().isEmpty();
    }

    /** Every installable catalogue key has a strategy row (or we forgot one). */
    public static boolean coversInstallableCatalogue() {
        return ProviderCatalog.all().stream()
                .filter(ProviderCatalog.Entry::installable)
                .allMatch(e -> BY_CLI.containsKey(e.key().toLowerCase(Locale.ROOT)));
    }

    private static Map.Entry<String, Strategy> entry(String cli, InputCounterKind kind, boolean hard, String notes) {
        return Map.entry(cli, new Strategy(kind, hard, notes));
    }
}
