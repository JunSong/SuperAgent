package com.superagent.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamProxyJoinUrlTest {

    @Test
    void stripsDuplicateV1WhenOpenAiBaseAlreadyIncludesIt() {
        assertThat(UpstreamProxy.joinUrl("https://api.openai.com/v1", "/v1/responses"))
                .isEqualTo("https://api.openai.com/v1/responses");
        assertThat(UpstreamProxy.joinUrl("https://api.openai.com/v1", "/v1/chat/completions"))
                .isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(UpstreamProxy.joinUrl("https://api.openai.com/v1", "/chat/completions"))
                .isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(UpstreamProxy.joinUrl("https://api.anthropic.com", "/v1/messages"))
                .isEqualTo("https://api.anthropic.com/v1/messages");
    }
}
