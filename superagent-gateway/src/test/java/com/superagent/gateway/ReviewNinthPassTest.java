package com.superagent.gateway;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.gateway.GatewayAccount;
import com.superagent.core.gateway.GatewayAccountType;
import com.superagent.core.gateway.GatewayAttempt;
import com.superagent.core.gateway.GatewayAttemptStatus;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayQuotaMode;
import com.superagent.core.tenant.TenantContext;
import com.superagent.gateway.count.InputCountRequest;
import com.superagent.gateway.count.InputCountResult;
import com.superagent.gateway.count.InputTokenCounterService;
import com.superagent.platform.provider.CredentialService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round-9 regression (L01): deadline expiry after beginSend but before HTTP must abortUnsent,
 * not markPending (known-unsent must release the freeze).
 */
class ReviewNinthPassTest {

    private static final byte[] BODY =
            "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":8}"
                    .getBytes();

    private static CredentialService credentials() {
        CredentialService c = mock(CredentialService.class);
        when(c.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(
                        new RunCredential("k1", "http://localhost/anthropic", "sk-test", true)));
        return c;
    }

    private static ModelRunGrant grant(Clock clock, String turnId) {
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://gw", clock);
        var lease = TenantContext.call("co1",
                () -> grants.issue(turnId, "run", "alice", "deepseek", "deepseek-chat", Duration.ofMinutes(5)));
        return grants.require("Bearer " + lease.token());
    }

    @Test
    void deadlineDuringBeginSendMustNotLeaveUnsentAttemptPending() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);
        GatewayMetering ledger = mock(GatewayMetering.class);
        when(ledger.findEmployee(eq("2026-11"), eq("alice"))).thenReturn(Optional.of(
                new GatewayAccount("2026-11", GatewayAccountType.EMPLOYEE, "alice",
                        GatewayQuotaMode.LIMITED, 1000, 0, 0, 1000, 0, null)));
        when(ledger.reserve(any())).thenReturn(new GatewayAttempt(
                "attempt", "req", 1, "t1", "run", "alice", "deepseek", "deepseek-chat", "2026-11",
                18, 0, GatewayAttemptStatus.RESERVED));
        when(ledger.beginSend(eq("attempt"))).thenAnswer(inv -> {
            Thread.sleep(600);
            return true;
        });

        HttpClient http = mock(HttpClient.class);
        InputTokenCounterService counter = new InputTokenCounterService() {
            @Override
            public InputCountResult count(InputCountRequest r) {
                return InputCountResult.provider(10, "stub");
            }
        };
        GatewayProxyTimeouts limits = new GatewayProxyTimeouts(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(300));
        UpstreamProxy proxy = new UpstreamProxy(
                credentials(), http, clock, new QuotaReserveGate(clock), limits, counter);
        proxy.setMetering(ledger);
        try {
            assertThatThrownBy(() -> proxy.forward(
                    grant(clock, "t1"), "anthropic", "/v1/messages", "POST",
                    Map.of(), BODY, new ByteArrayOutputStream(), null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("upstream request timeout");
        } finally {
            proxy.stopIdleWatch();
        }
        verify(ledger).beginSend("attempt");
        verify(http, never()).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verify(ledger, never()).markPending("attempt");
        verify(ledger).abortUnsent("attempt");
    }
}
