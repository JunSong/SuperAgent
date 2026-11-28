package com.superagent.gateway;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.gateway.GatewayAccount;
import com.superagent.core.gateway.GatewayAccountType;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayQuotaMode;
import com.superagent.core.tenant.TenantContext;
import com.superagent.gateway.count.InputCountRequest;
import com.superagent.gateway.count.InputCountResult;
import com.superagent.gateway.count.InputTokenCounterService;
import com.superagent.platform.provider.CredentialService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round-8 regression: same-turn float must pass the pre-count gate (K01);
 * soft count that burns the whole-request budget must not start inference (K02).
 */
class ReviewEighthPassTest {

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
    void sameTurnAtExactBaseMustReachFloatReservation() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);
        GatewayMetering ledger = mock(GatewayMetering.class);
        // Exact-B settled turn with F still unclaimed: storage permits this turn to continue.
        when(ledger.findEmployee(eq("2026-11"), eq("alice"))).thenReturn(Optional.of(
                new GatewayAccount("2026-11", GatewayAccountType.EMPLOYEE, "alice",
                        GatewayQuotaMode.LIMITED, 0, 0, 100, 100, 50, null)));
        when(ledger.hasAttemptForTurn(eq("2026-11"), eq("alice"), eq("existing-turn"))).thenReturn(true);
        when(ledger.reserve(any())).thenThrow(new IllegalStateException("reached authoritative float reservation"));

        AtomicInteger counts = new AtomicInteger();
        InputTokenCounterService counter = new InputTokenCounterService() {
            @Override
            public InputCountResult count(InputCountRequest r) {
                counts.incrementAndGet();
                return InputCountResult.provider(10, "stub");
            }
        };
        UpstreamProxy proxy = new UpstreamProxy(
                credentials(), mock(HttpClient.class), clock, new QuotaReserveGate(clock),
                GatewayProxyTimeouts.TESTS, counter);
        proxy.setMetering(ledger);

        assertThatThrownBy(() -> proxy.forward(
                grant(clock, "existing-turn"), "anthropic", "/v1/messages", "POST",
                Map.of(), BODY, new ByteArrayOutputStream(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reached authoritative float reservation");
        assertThat(counts.get()).as("eligible continuation reaches count before authoritative reserve").isEqualTo(1);
        verify(ledger).reserve(any());
    }

    @Test
    void newTurnAtExactBaseWithFloatMustNotReachVendorCount() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);
        GatewayMetering ledger = mock(GatewayMetering.class);
        when(ledger.findEmployee(eq("2026-11"), eq("alice"))).thenReturn(Optional.of(
                new GatewayAccount("2026-11", GatewayAccountType.EMPLOYEE, "alice",
                        GatewayQuotaMode.LIMITED, 0, 0, 100, 100, 50, null)));
        when(ledger.hasAttemptForTurn(eq("2026-11"), eq("alice"), eq("brand-new-turn"))).thenReturn(false);

        AtomicInteger counts = new AtomicInteger();
        InputTokenCounterService counter = new InputTokenCounterService() {
            @Override
            public InputCountResult count(InputCountRequest r) {
                counts.incrementAndGet();
                return InputCountResult.provider(10, "stub");
            }
        };
        UpstreamProxy proxy = new UpstreamProxy(
                credentials(), mock(HttpClient.class), clock, new QuotaReserveGate(clock),
                GatewayProxyTimeouts.TESTS, counter);
        proxy.setMetering(ledger);

        assertThatThrownBy(() -> proxy.forward(
                grant(clock, "brand-new-turn"), "anthropic", "/v1/messages", "POST",
                Map.of(), BODY, new ByteArrayOutputStream(), null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("额度已用尽");
        assertThat(counts.get()).isZero();
        verify(ledger, never()).reserve(any());
    }

    @Test
    void expiredCountBudgetMustNotSendInference() throws Exception {
        Clock clock = Clock.systemUTC();
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(resp.statusCode()).thenReturn(200);
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(resp));

        InputTokenCounterService counter = new InputTokenCounterService() {
            @Override
            public InputCountResult count(InputCountRequest r) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                // Soft count timeout is converted to heuristic by the production counter service.
                return InputCountResult.heuristic(10);
            }
        };
        GatewayProxyTimeouts limits = new GatewayProxyTimeouts(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(30));
        UpstreamProxy proxy = new UpstreamProxy(
                credentials(), http, clock, new QuotaReserveGate(clock), limits, counter);
        try {
            assertThatThrownBy(() -> proxy.forward(
                    grant(clock, "t1"), "anthropic", "/v1/messages", "POST",
                    Map.of(), BODY, new ByteArrayOutputStream(), null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("upstream request timeout");
        } finally {
            proxy.stopIdleWatch();
        }
        verify(http, never()).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
