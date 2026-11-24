package com.superagent.platform.provider;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.gateway.ManagedRun;
import com.superagent.core.gateway.ModelRunLease;
import com.superagent.core.gateway.ModelRunLeaseService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedRunFactoryTest {

    @Test
    void unmanagedReturnsCompanyCredential() {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current("deepseek"))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", "https://api", "sk-co", false)));
        ModelRunLeaseService leases = mock(ModelRunLeaseService.class);
        when(leases.managed("deepseek")).thenReturn(false);

        ManagedRunFactory factory = new ManagedRunFactory(credentials);
        factory.setLeases(leases);

        ManagedRun run = factory.open("deepseek", "turn-1", "alice", "deepseek-chat");
        assertThat(run.managed()).isFalse();
        assertThat(run.credential().apiKey()).isEqualTo("sk-co");
        assertThat(run.revokeToken()).isNull();
    }

    @Test
    void managedIssuesGatewayTokenAndRequiresCompanyKey() {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current("deepseek"))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", "https://api", "sk-co", false)));
        ModelRunLeaseService leases = mock(ModelRunLeaseService.class);
        when(leases.managed("deepseek")).thenReturn(true);
        when(leases.issue(eq("turn-1"), eq("run-1"), eq("alice"), eq("deepseek"), eq("deepseek-chat"), any()))
                .thenReturn(new ModelRunLease("tok", "http://gw", "run-1", "turn-1", "deepseek", "deepseek-chat"));

        ManagedRunFactory factory = new ManagedRunFactory(credentials);
        factory.setLeases(leases);

        ManagedRun run = factory.open("deepseek", "turn-1", "run-1", "alice", "deepseek-chat", Duration.ofHours(1));
        assertThat(run.managed()).isTrue();
        assertThat(run.credential().apiKey()).isEqualTo("tok");
        assertThat(run.credential().baseUrl()).isEqualTo("http://gw");
        assertThat(run.credential().bearer()).isTrue();
        assertThat(run.revokeToken()).isEqualTo("tok");
    }

    @Test
    void managedWithoutCompanyKeyIsForbidden() {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current("deepseek"))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", null, null, false)));
        ModelRunLeaseService leases = mock(ModelRunLeaseService.class);
        when(leases.managed("deepseek")).thenReturn(true);

        ManagedRunFactory factory = new ManagedRunFactory(credentials);
        factory.setLeases(leases);

        assertThatThrownBy(() -> factory.open("deepseek", "t", "alice", "m"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void closeRevokesLease() {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.current("deepseek"))
                .thenReturn(new CredentialService.Choice(new RunCredential("k1", "https://api", "sk", false)));
        ModelRunLeaseService leases = mock(ModelRunLeaseService.class);
        when(leases.managed("deepseek")).thenReturn(true);
        when(leases.issue(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ModelRunLease("tok", "http://gw", "r", "t", "deepseek", "m"));

        ManagedRunFactory factory = new ManagedRunFactory(credentials);
        factory.setLeases(leases);
        ManagedRun run = factory.open("deepseek", "t", "alice", "m");
        run.close(leases);
        verify(leases).revoke("tok");
    }
}
