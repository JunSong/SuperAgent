package com.superagent.platform.provider;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.gateway.ManagedRun;
import com.superagent.core.gateway.ModelRunLease;
import com.superagent.core.gateway.ModelRunLeaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Builds the {@link RunCredential} a CLI process should see: either the legacy company Key, or a gateway
 * base URL + opaque run token when the CLI is on {@code superagent.gateway.managed-clis}.
 */
@Component
public class ManagedRunFactory {

    private final CredentialService credentials;
    private ModelRunLeaseService leases;

    /**
     * {@code @Lazy}: CredentialService → ProviderInferenceProbe → this factory would otherwise cycle at startup.
     */
    public ManagedRunFactory(@Lazy CredentialService credentials) {
        this.credentials = credentials;
    }

    @Autowired(required = false)
    public void setLeases(ModelRunLeaseService leases) {
        this.leases = leases;
    }

    /**
     * @param turnId chat {@code userMessageId} or task id for this execution
     * @param payerUserId who the tokens are billed to
     */
    public ManagedRun open(String cli, String turnId, String payerUserId, String model) {
        return open(cli, turnId, UUID.randomUUID().toString(), payerUserId, model, Duration.ofHours(2));
    }

    public ManagedRun open(String cli, String turnId, String runId, String payerUserId, String model, Duration ttl) {
        CredentialService.Choice choice = credentials.current(cli);
        if (leases == null || !leases.managed(cli)) {
            return ManagedRun.legacy(choice.credential());
        }
        RunCredential company = choice.credential();
        if (company == null || company.apiKey() == null || company.apiKey().isBlank()) {
            throw new ForbiddenException("本公司还没有配置 " + cli + " 的 API Key：受管网关不能使用机器上的登录态");
        }
        ModelRunLease lease = leases.issue(turnId, runId, payerUserId, cli, model, ttl);
        return ManagedRun.ofLease(lease);
    }

    /** Whether this CLI is on the managed list (tests / callers that skip open). */
    public boolean managed(String cli) {
        return leases != null && leases.managed(cli);
    }
}
