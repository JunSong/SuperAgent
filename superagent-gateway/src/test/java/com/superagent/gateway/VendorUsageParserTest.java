package com.superagent.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VendorUsageParserTest {

    @Test
    void openaiChatCompletions() {
        var s = VendorUsageParser.parse("""
                {"usage":{"prompt_tokens":100,"completion_tokens":40,"prompt_tokens_details":{"cached_tokens":30}}}
                """).orElseThrow();
        assertThat(s.total()).isEqualTo(140);
    }

    @Test
    void anthropicMessages() {
        var s = VendorUsageParser.parse("""
                {"usage":{"input_tokens":70,"cache_read_input_tokens":30,"cache_creation_input_tokens":0,"output_tokens":40}}
                """).orElseThrow();
        assertThat(s.total()).isEqualTo(140);
    }

    @Test
    void responsesCompletedNestedUsage() {
        var s = VendorUsageParser.parse("""
                {"type":"response.completed","response":{"usage":{"input_tokens":50,"output_tokens":8}}}
                """).orElseThrow();
        assertThat(s.total()).isEqualTo(58);
    }

    @Test
    void ignoresDoneAndEmpty() {
        assertThat(VendorUsageParser.parse("[DONE]")).isEmpty();
        assertThat(VendorUsageParser.parse("{}")).isEmpty();
    }
}
