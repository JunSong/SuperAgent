package com.superagent.gateway;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.gateway.ManagedRun;
import com.superagent.core.tenant.TenantContext;
import com.superagent.platform.provider.CredentialService;
import com.superagent.platform.provider.ManagedRunFactory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-02 acceptance (#34): managed entries share turn identity; hop keeps turnId and issues a new run;
 * unauthorized / revoked tokens stay 0 outbound (covered with require).
 */
class GatewayManagedEntriesAcceptanceTest {

    @Test
    void handoffAndReplyShareTurnAndPayerIsSender() {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", "https://api", "sk-co", false)));
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://gw", Clock.systemUTC());
        ManagedRunFactory factory = new ManagedRunFactory(credentials);
        factory.setLeases(grants);

        String turnId = UUID.randomUUID().toString(); // chat userMessageId
        String sender = "bob"; // not the agent owner

        ManagedRun handoff = TenantContext.call("co1",
                () -> factory.open("deepseek", turnId, "run-handoff", sender, "deepseek-chat", Duration.ofHours(1)));
        ManagedRun reply = TenantContext.call("co1",
                () -> factory.open("deepseek", turnId, "run-reply", sender, "deepseek-chat", Duration.ofHours(1)));

        ModelRunGrant h = grants.require("Bearer " + handoff.revokeToken());
        ModelRunGrant r = grants.require("Bearer " + reply.revokeToken());
        assertThat(h.turnId()).isEqualTo(turnId);
        assertThat(r.turnId()).isEqualTo(turnId);
        assertThat(h.payerUserId()).isEqualTo(sender);
        assertThat(r.payerUserId()).isEqualTo(sender);
        assertThat(h.runId()).isNotEqualTo(r.runId());

        handoff.close(grants);
        reply.close(grants);
    }

    @Test
    void modelHopKeepsTurnIdAndIssuesNewRun() {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", "https://api", "sk", false)));
        when(credentials.current(eq("claude")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k2", "https://api", "sk2", false)));
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek", "claude"), "http://gw", Clock.systemUTC());
        ManagedRunFactory factory = new ManagedRunFactory(credentials);
        factory.setLeases(grants);

        String turnId = "user-msg-1";
        ManagedRun first = TenantContext.call("co1",
                () -> factory.open("deepseek", turnId, "run-1", "alice", "deepseek-chat", Duration.ofHours(1)));
        String firstToken = first.revokeToken();
        first.close(grants);

        ManagedRun hop = TenantContext.call("co1",
                () -> factory.open("claude", turnId, "run-2", "alice", "claude-sonnet", Duration.ofHours(1)));

        assertThatThrownBy(() -> grants.require("Bearer " + firstToken))
                .isInstanceOf(ModelRunGrants.UnauthorizedGatewayException.class);

        ModelRunGrant g = grants.require("Bearer " + hop.revokeToken());
        assertThat(g.turnId()).isEqualTo(turnId);
        assertThat(g.runId()).isEqualTo("run-2");
        assertThat(g.cli()).isEqualTo("claude");
        hop.close(grants);
    }

    @Test
    void taskTurnUsesTaskIdAsTurnId() {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current(eq("deepseek")))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", "https://api", "sk", false)));
        ModelRunGrants grants = new ModelRunGrants(Set.of("deepseek"), "http://gw", Clock.systemUTC());
        ManagedRunFactory factory = new ManagedRunFactory(credentials);
        factory.setLeases(grants);

        String taskId = "task-abc";
        ManagedRun run = TenantContext.call("co1",
                () -> factory.open("deepseek", taskId, "creator", "deepseek-chat"));
        ModelRunGrant g = grants.require("Bearer " + run.revokeToken());
        assertThat(g.turnId()).isEqualTo(taskId);
        assertThat(g.payerUserId()).isEqualTo("creator");
        run.close(grants);
    }
}
