package com.superagent.gateway;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingUsageProbeTest {

    @Test
    void readsUsageFromFinalSseChunk() {
        StreamingUsageProbe probe = new StreamingUsageProbe();
        feed(probe, "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n");
        feed(probe, "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}\n\n");
        feed(probe, "data: [DONE]\n\n");
        probe.finish();
        assertThat(probe.total()).hasValue(15);
    }

    @Test
    void mergesAnthropicSplitUsage() {
        StreamingUsageProbe probe = new StreamingUsageProbe();
        feed(probe, "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":50,\"output_tokens\":0}}}\n\n");
        feed(probe, "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":8}}\n\n");
        feed(probe, "data: {\"type\":\"message_stop\"}\n\n");
        probe.finish();
        assertThat(probe.total()).hasValue(58);
    }

    @Test
    void incompleteAnthropicStreamMustRemainPending() {
        StreamingUsageProbe probe = new StreamingUsageProbe();
        feed(probe, "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":50,\"output_tokens\":0}}}\n\n");
        feed(probe, "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}\n\n");
        probe.finish();
        assertThat(probe.total()).isEmpty();
    }

    @Test
    void responsesCompletedUsageMustBeRead() {
        StreamingUsageProbe probe = new StreamingUsageProbe();
        feed(probe, "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":50,\"output_tokens\":8}}}\n\n");
        probe.finish();
        assertThat(probe.total()).hasValue(58);
    }

    @Test
    void nonStreamJsonBody() {
        StreamingUsageProbe probe = new StreamingUsageProbe();
        feed(probe, "{\"id\":\"x\",\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}");
        probe.finish();
        assertThat(probe.total()).hasValue(7);
    }

    @Test
    void missingUsageStaysEmpty() {
        StreamingUsageProbe probe = new StreamingUsageProbe();
        feed(probe, "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n");
        probe.finish();
        assertThat(probe.total()).isEmpty();
    }

    private static void feed(StreamingUsageProbe probe, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        // split mid-buffer to exercise decoder/line assembly
        int mid = Math.max(1, b.length / 2);
        probe.accept(b, 0, mid);
        probe.accept(b, mid, b.length - mid);
    }
}
