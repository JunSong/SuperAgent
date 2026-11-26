package com.superagent.gateway;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationEstimatorTest {

    @Test
    void usesBodyBytesAndMaxTokens() {
        byte[] body = "{\"model\":\"x\",\"max_tokens\":100,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        long r = ReservationEstimator.estimate(body);
        assertThat(r).isEqualTo((body.length + 3) / 4 + 256); // output floor wins over 100
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
    void injectsOutputCapWhenMissingAndClampsMaxCompletionTokens() {
        byte[] missing = "{\"model\":\"x\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        ReservationEstimator.Prepared prepared = ReservationEstimator.prepare(missing);
        assertThat(prepared.outputCap()).isEqualTo(256);
        String out = ReservationEstimator.asUtf8(prepared.body());
        assertThat(out).contains("\"max_tokens\":256");
        assertThat(out).contains("\"max_output_tokens\":256");
        assertThat(out).contains("\"max_completion_tokens\":256");

        byte[] completion = "{\"max_completion_tokens\":256000}".getBytes(StandardCharsets.UTF_8);
        ReservationEstimator.Prepared clamped = ReservationEstimator.prepare(completion);
        assertThat(clamped.outputCap()).isEqualTo(ReservationEstimator.OUTPUT_RESERVE_CAP);
        assertThat(ReservationEstimator.asUtf8(clamped.body())).contains("\"max_completion_tokens\":32768");
    }
}
