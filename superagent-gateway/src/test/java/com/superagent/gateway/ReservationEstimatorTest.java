package com.superagent.gateway;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationEstimatorTest {

    @Test
    void usesBodyBytesAndDeclaredMaxTokens() {
        byte[] body = "{\"model\":\"x\",\"max_tokens\":100,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        long r = ReservationEstimator.estimate(body);
        assertThat(r).isEqualTo((body.length + 3) / 4 + 100); // do not raise 100 → 256
    }

    @Test
    void respectsLargerOutputCap() {
        byte[] body = "{\"max_output_tokens\":1000}".getBytes(StandardCharsets.UTF_8);
        assertThat(ReservationEstimator.estimate(body)).isEqualTo((body.length + 3) / 4 + 1000);
    }

    @Test
    void capsHarnessSizedMaxTokens() {
        byte[] body = "{\"max_tokens\":256000,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        assertThat(ReservationEstimator.estimate(body))
                .isEqualTo((body.length + 3) / 4 + ReservationEstimator.OUTPUT_RESERVE_CAP);
    }

    @Test
    void clampsOutboundMaxTokensToReservedOutput() {
        byte[] body = "{\"max_tokens\":256000,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        ReservationEstimator.Prepared prepared = ReservationEstimator.prepare(body);
        assertThat(prepared.outputCap()).isEqualTo(ReservationEstimator.OUTPUT_RESERVE_CAP);
        assertThat(ReservationEstimator.asUtf8(prepared.body())).contains("\"max_tokens\":32768");
        assertThat(ReservationEstimator.asUtf8(prepared.body())).doesNotContain("256000");
    }

    @Test
    void doesNotRaiseSmallDeclaredCap() {
        byte[] body = "{\"max_tokens\":8,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        ReservationEstimator.Prepared prepared =
                ReservationEstimator.prepare(body, ModelRunGrants.PROTOCOL_ANTHROPIC, "/v1/messages");
        assertThat(prepared.outputCap()).isEqualTo(8);
        assertThat(ReservationEstimator.asUtf8(prepared.body())).contains("\"max_tokens\":8");
        assertThat(ReservationEstimator.asUtf8(prepared.body())).doesNotContain("256");
    }

    @Test
    void injectsOnlyProtocolFieldWhenMissing() {
        byte[] missing = "{\"model\":\"x\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        ReservationEstimator.Prepared anthropic =
                ReservationEstimator.prepare(missing, ModelRunGrants.PROTOCOL_ANTHROPIC, "/v1/messages");
        String a = ReservationEstimator.asUtf8(anthropic.body());
        assertThat(a).contains("\"max_tokens\":256");
        assertThat(a).doesNotContain("max_output_tokens");
        assertThat(a).doesNotContain("max_completion_tokens");

        ReservationEstimator.Prepared responses =
                ReservationEstimator.prepare(missing, ModelRunGrants.PROTOCOL_OPENAI, "/v1/responses");
        String r = ReservationEstimator.asUtf8(responses.body());
        assertThat(r).contains("\"max_output_tokens\":256");
        assertThat(r).doesNotContain("\"max_tokens\"");
        assertThat(r).doesNotContain("max_completion_tokens");

        byte[] completion = "{\"max_completion_tokens\":256000}".getBytes(StandardCharsets.UTF_8);
        ReservationEstimator.Prepared clamped =
                ReservationEstimator.prepare(completion, ModelRunGrants.PROTOCOL_OPENAI, "/v1/chat/completions");
        assertThat(clamped.outputCap()).isEqualTo(ReservationEstimator.OUTPUT_RESERVE_CAP);
        String c = ReservationEstimator.asUtf8(clamped.body());
        assertThat(c).contains("\"max_completion_tokens\":32768");
        assertThat(c).doesNotContain("max_tokens");
        assertThat(c).doesNotContain("max_output_tokens");
    }
}
