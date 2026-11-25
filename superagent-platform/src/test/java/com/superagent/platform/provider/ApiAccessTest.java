package com.superagent.platform.provider;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.provider.ProviderCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAccessTest {

    @Test
    void deepseekRootStripsChatCompletionsAndAnthropic() {
        assertThat(ApiAccess.deepseekApiRoot("https://api.deepseek.com/chat/completions"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(ApiAccess.deepseekApiRoot("https://api.deepseek.com/anthropic"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(ApiAccess.deepseekApiRoot("https://api.deepseek.com/v1/"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(ApiAccess.normalizeDeepseekAnthropicBase("https://api.deepseek.com/chat/completions"))
                .isEqualTo("https://api.deepseek.com/anthropic");
    }

    @Test
    void deepseekModelsUrlNotBuiltOnChatCompletions() {
        var entry = ProviderCatalog.find("deepseek").orElseThrow();
        ApiAccess access = ApiAccess.of(entry,
                new RunCredential("id", "https://api.deepseek.com/chat/completions", "sk-x", false)).orElseThrow();
        assertThat(access.base()).isEqualTo("https://api.deepseek.com/anthropic");
        assertThat(access.modelsUrl("deepseek")).isEqualTo("https://api.deepseek.com/models");
    }
}
