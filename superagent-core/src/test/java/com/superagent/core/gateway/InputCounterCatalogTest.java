package com.superagent.core.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputCounterCatalogTest {

    @Test
    void coversEveryInstallableCatalogueCli() {
        assertThat(InputCounterCatalog.coversInstallableCatalogue()).isTrue();
    }

    @Test
    void deepseekIsHardEligibleAnthropicCount() {
        assertThat(InputCounterCatalog.require("deepseek").kind())
                .isEqualTo(InputCounterKind.PROVIDER_ANTHROPIC_COUNT);
        assertThat(InputCounterCatalog.require("deepseek").hardEligible()).isTrue();
        assertThat(InputCounterCatalog.hardEligibleClis()).containsExactly("deepseek");
        assertThat(InputCounterCatalog.hardInputBoundAvailable()).isTrue();
    }

    @Test
    void grokUsesXaiTokenizeButNotHardYet() {
        assertThat(InputCounterCatalog.require("grok").kind()).isEqualTo(InputCounterKind.PROVIDER_XAI_TOKENIZE);
        assertThat(InputCounterCatalog.require("grok").hardEligible()).isFalse();
    }
}
