package com.superagent.gateway;

import com.superagent.core.error.ForbiddenException;
import com.superagent.core.gateway.GatewayInputBoundMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationEstimatorTest {

    @Test
    void usesBodyBytesAndDeclaredMaxTokens() {
        byte[] body = "{\"model\":\"x\",\"max_tokens\":100,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        long r = ReservationEstimator.estimate(body);
        assertThat(r).isEqualTo((body.length + 3) / 4 + 100); // do not raise 100 → 256
        assertThat(ReservationEstimator.prepare(body).inputBoundMode()).isEqualTo(GatewayInputBoundMode.HEURISTIC);
    }

    @Test
    void respectsLargerOutputCapOnResponses() {
        byte[] body = "{\"max_output_tokens\":1000}".getBytes(StandardCharsets.UTF_8);
        assertThat(ReservationEstimator.prepare(body, ModelRunGrants.PROTOCOL_OPENAI, "/v1/responses").reservedTokens())
                .isEqualTo((body.length + 3) / 4 + 1000);
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
        assertThat(c).doesNotContain("\"max_tokens\"");
        assertThat(c).doesNotContain("max_output_tokens");
    }

    @Test
    void chatIgnoresForeignMaxOutputTokensForDeclaredButClampsIt() {
        // H1 + NEW-2: foreign field must not set R, and must not reach the vendor uncapped.
        byte[] body = "{\"model\":\"x\",\"max_output_tokens\":1,\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        ReservationEstimator.Prepared prepared =
                ReservationEstimator.prepare(body, ModelRunGrants.PROTOCOL_OPENAI, "/v1/chat/completions");
        assertThat(prepared.outputCap()).isEqualTo(256);
        String out = ReservationEstimator.asUtf8(prepared.body());
        assertThat(out).contains("\"max_tokens\":256");
        assertThat(out).contains("\"max_output_tokens\":256");
    }

    @Test
    void responsesDeclaredFromMaxOutputTokensAndClampsForeignMaxTokens() {
        // H1 reverse + NEW-2: leftover max_tokens must not shrink R, but is clamped to output.
        byte[] body = "{\"model\":\"x\",\"max_tokens\":1,\"max_output_tokens\":8000}".getBytes(StandardCharsets.UTF_8);
        ReservationEstimator.Prepared prepared =
                ReservationEstimator.prepare(body, ModelRunGrants.PROTOCOL_OPENAI, "/v1/responses");
        assertThat(prepared.outputCap()).isEqualTo(8000);
        String out = ReservationEstimator.asUtf8(prepared.body());
        assertThat(out).contains("\"max_output_tokens\":8000");
        assertThat(out).contains("\"max_tokens\":8000");
    }

    @Test
    void chatDualFieldCannotUnderReserveViaMaxCompletionTokens() {
        // NEW-2: preferred=max_completion_tokens → R=input+1, but max_tokens also clamped to 1.
        byte[] body = "{\"model\":\"x\",\"max_tokens\":100000,\"max_completion_tokens\":1}"
                .getBytes(StandardCharsets.UTF_8);
        ReservationEstimator.Prepared prepared =
                ReservationEstimator.prepare(body, ModelRunGrants.PROTOCOL_OPENAI, "/v1/chat/completions");
        assertThat(prepared.outputCap()).isEqualTo(1);
        assertThat(prepared.reservedTokens()).isEqualTo((body.length + 3) / 4 + 1);
        String out = ReservationEstimator.asUtf8(prepared.body());
        assertThat(out).contains("\"max_completion_tokens\":1");
        assertThat(out).contains("\"max_tokens\":1");
        assertThat(out).doesNotContain("100000");
    }

    @Test
    void rejectsNonJsonBody() {
        assertThatThrownBy(() -> ReservationEstimator.prepare(
                "not-json".getBytes(StandardCharsets.UTF_8),
                ModelRunGrants.PROTOCOL_OPENAI, "/v1/chat/completions"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void rejectsJsonArrayBody() {
        assertThatThrownBy(() -> ReservationEstimator.prepare(
                "[]".getBytes(StandardCharsets.UTF_8),
                ModelRunGrants.PROTOCOL_OPENAI, "/v1/chat/completions"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void rejectsEmptyBody() {
        assertThatThrownBy(() -> ReservationEstimator.prepare(
                new byte[0], ModelRunGrants.PROTOCOL_ANTHROPIC, "/v1/messages"))
                .isInstanceOf(ForbiddenException.class);
    }
}
