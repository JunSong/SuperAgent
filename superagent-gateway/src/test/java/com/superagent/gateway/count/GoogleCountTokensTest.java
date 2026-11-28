package com.superagent.gateway.count;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleCountTokensTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void refusesBlankBaseInsteadOfSilentGoogleDefault() {
        assertThatThrownBy(() -> GoogleCountTokens.requireCountRoot(null))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("configured https base");
        assertThatThrownBy(() -> GoogleCountTokens.requireCountRoot("  "))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("configured https base");
    }

    @Test
    void refusesNonHttpsNonLoopback() {
        assertThatThrownBy(() -> GoogleCountTokens.requireCountRoot("http://evil.example/v1beta"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("https");
    }

    @Test
    void refusesLookalikeGoogleHost() {
        assertThatThrownBy(() ->
                GoogleCountTokens.requireCountRoot("https://generativelanguage.evil.example/v1beta"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void acceptsOfficialGoogleHostAndLoopbackHttp() throws Exception {
        assertThat(GoogleCountTokens.requireCountRoot("https://generativelanguage.googleapis.com/v1beta"))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta");
        assertThat(GoogleCountTokens.requireCountRoot("http://127.0.0.1:9/v1beta"))
                .isEqualTo("http://127.0.0.1:9/v1beta");
    }

    @Test
    void acceptsExplicitCorpGatewayWithoutRewriting() throws Exception {
        assertThat(GoogleCountTokens.requireCountRoot("https://gateway.corp.internal/gemini"))
                .isEqualTo("https://gateway.corp.internal/gemini");
    }

    @Test
    void countPayloadKeepsSystemAndTools() throws Exception {
        JsonNode root = JSON.readTree("""
                {
                  "model": "gemini-2.0-flash",
                  "systemInstruction": {"parts":[{"text":"be brief"}]},
                  "contents":[{"role":"user","parts":[{"text":"hi"}]}],
                  "tools":[{"functionDeclarations":[{"name":"x"}]}],
                  "toolConfig":{"functionCallingConfig":{"mode":"AUTO"}}
                }
                """);
        var payload = GoogleCountTokens.buildCountPayload(root);
        assertThat(payload.has("systemInstruction")).isTrue();
        assertThat(payload.has("tools")).isTrue();
        assertThat(payload.has("toolConfig")).isTrue();
        assertThat(payload.get("contents")).isNotNull();
    }
}
