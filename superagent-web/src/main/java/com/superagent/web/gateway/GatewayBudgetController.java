package com.superagent.web.gateway;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.gateway.GatewayBudgetDtos;
import com.superagent.core.gateway.GatewayAccount;
import com.superagent.core.gateway.GatewayAccountType;
import com.superagent.core.gateway.GatewayAttempt;
import com.superagent.core.gateway.GatewayAttemptStatus;
import com.superagent.core.gateway.GatewayLedgerEntry;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayPeriods;
import com.superagent.core.gateway.GatewayPolicy;
import com.superagent.core.gateway.GatewayProjectionEvent;
import com.superagent.core.gateway.GatewayReportMode;
import com.superagent.gateway.GatewayPolicyService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * Session-authenticated budget APIs for the model gateway (#34 PR-05).
 * Model data plane stays on {@link GatewayController} with run tokens.
 */
@RestController
public class GatewayBudgetController {

    private final GatewayMetering metering;
    private final GatewayPolicyService policy;
    private final Clock clock;

    public GatewayBudgetController(GatewayMetering metering, GatewayPolicyService policy, Clock clock) {
        this.metering = metering;
        this.policy = policy;
        this.clock = clock;
    }

    @GetMapping(ApiPaths.GATEWAY_ME + "/balance")
    public ApiResponse<GatewayBudgetDtos.Balance> myBalance(
            @RequestParam(required = false) String period, Authentication auth) {
        String p = periodOrCurrent(period);
        GatewayAccount a = metering.findEmployee(p, auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "本月尚未分配额度"));
        return ApiResponse.ok(toBalance(a));
    }

    @GetMapping(ApiPaths.GATEWAY_ME + "/ledger")
    public ApiResponse<GatewayBudgetDtos.LedgerPage> myLedger(
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "50") int limit,
            Authentication auth) {
        String p = periodOrCurrent(period);
        List<GatewayBudgetDtos.LedgerLine> lines = metering.listLedger(p, limit).stream()
                .filter(e -> auth.getName().equals(e.ownerId()))
                .map(GatewayBudgetController::toLine)
                .toList();
        return ApiResponse.ok(new GatewayBudgetDtos.LedgerPage(lines));
    }

    @PostMapping(ApiPaths.GATEWAY_ME + "/transfers")
    public ApiResponse<Void> transfer(@RequestBody GatewayBudgetDtos.TransferRequest body, Authentication auth) {
        requireBody(body);
        String period = periodOrCurrent(body.period());
        metering.transfer(body.operationId(), period, auth.getName(), body.toUserId(), body.amount(), auth.getName());
        return ApiResponse.ok(null);
    }

    @GetMapping(ApiPaths.GATEWAY_ADMIN + "/pools")
    public ApiResponse<GatewayBudgetDtos.Balance> getPool(
            @RequestParam(defaultValue = "UNALLOCATED") String type,
            @RequestParam(required = false) String period) {
        GatewayAccountType t;
        try {
            t = GatewayAccountType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown pool type: " + type);
        }
        if (t == GatewayAccountType.EMPLOYEE) {
            throw new IllegalArgumentException("use /balances for employee accounts");
        }
        String p = periodOrCurrent(period);
        GatewayAccount a = metering.findAccount(p, t, "")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "池尚未注入：" + t.name()));
        return ApiResponse.ok(toBalance(a));
    }

    @PutMapping(ApiPaths.GATEWAY_ADMIN + "/pools")
    public ApiResponse<GatewayBudgetDtos.Balance> putPool(@RequestBody GatewayBudgetDtos.PutPoolRequest body) {
        if (body == null || body.type() == null) {
            throw new IllegalArgumentException("type is required");
        }
        GatewayAccountType type = GatewayAccountType.valueOf(body.type());
        String period = periodOrCurrent(body.period());
        metering.putPool(period, type, body.available());
        return ApiResponse.ok(toBalance(metering.findAccount(period, type, "").orElseThrow()));
    }

    @PostMapping(ApiPaths.GATEWAY_ADMIN + "/allocations")
    public ApiResponse<GatewayBudgetDtos.Balance> allocate(
            @RequestBody GatewayBudgetDtos.AllocateRequest body, Authentication auth) {
        requireBody(body);
        String period = periodOrCurrent(body.period());
        // Period first-touch: top up RECON from UNALLOCATED (never mint) so A>R settle can succeed.
        metering.fundReconReserveToTarget(period, policy.get().reconReserveTargetTokens());
        GatewayAccount allocated = metering.allocate(
                body.operationId(), period, body.userId(), body.amount(), auth.getName());
        return ApiResponse.ok(toBalance(applyFloatAfterAllocate(period, body.userId(), body.floatAllowance(), allocated)));
    }

    @PutMapping(ApiPaths.GATEWAY_ADMIN + "/floats")
    public ApiResponse<GatewayBudgetDtos.Balance> setFloat(@RequestBody GatewayBudgetDtos.SetFloatRequest body) {
        requireBody(body);
        if (body.userId() == null || body.userId().isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (body.floatAllowance() < 0) {
            throw new IllegalArgumentException("floatAllowance must be >= 0");
        }
        String period = periodOrCurrent(body.period());
        return ApiResponse.ok(toBalance(metering.setFloatAllowance(period, body.userId(), body.floatAllowance())));
    }

    @PostMapping(ApiPaths.GATEWAY_ADMIN + "/float-defaults")
    public ApiResponse<GatewayBudgetDtos.ApplyFloatDefaultsResult> applyFloatDefaults(
            @RequestBody(required = false) GatewayBudgetDtos.ApplyFloatDefaultsRequest body) {
        String period = periodOrCurrent(body == null ? null : body.period());
        long f = policy.get().defaultFloatAllowance();
        int updated = metering.applyDefaultFloat(period, f);
        ensureFloatPoolAtLeast(period, f);
        return ApiResponse.ok(new GatewayBudgetDtos.ApplyFloatDefaultsResult(updated, f));
    }

    @PostMapping(ApiPaths.GATEWAY_ADMIN + "/reclaims")
    public ApiResponse<GatewayBudgetDtos.Balance> reclaim(
            @RequestBody GatewayBudgetDtos.ReclaimRequest body, Authentication auth) {
        requireBody(body);
        String period = periodOrCurrent(body.period());
        return ApiResponse.ok(toBalance(
                metering.reclaim(body.operationId(), period, body.userId(), body.amount(), auth.getName())));
    }

    @GetMapping(ApiPaths.GATEWAY_ADMIN + "/ledger")
    public ApiResponse<GatewayBudgetDtos.LedgerPage> adminLedger(
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "100") int limit) {
        String p = periodOrCurrent(period);
        return ApiResponse.ok(new GatewayBudgetDtos.LedgerPage(
                metering.listLedger(p, limit).stream().map(GatewayBudgetController::toLine).toList()));
    }

    /**
     * Employee balances for the period. Omit {@code userId} for the full roster;
     * with {@code userId}, returns that one account (404 if missing).
     */
    @GetMapping(ApiPaths.GATEWAY_ADMIN + "/balances")
    public ApiResponse<GatewayBudgetDtos.BalancePage> adminBalances(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String period) {
        String p = periodOrCurrent(period);
        if (userId != null && !userId.isBlank()) {
            GatewayAccount a = metering.findEmployee(p, userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到员工额度账户"));
            return ApiResponse.ok(new GatewayBudgetDtos.BalancePage(List.of(toBalance(a))));
        }
        return ApiResponse.ok(new GatewayBudgetDtos.BalancePage(
                metering.listEmployees(p).stream().map(GatewayBudgetController::toBalance).toList()));
    }

    @GetMapping(ApiPaths.GATEWAY_ADMIN + "/attempts")
    public ApiResponse<GatewayBudgetDtos.AttemptPage> adminAttempts(
            @RequestParam(defaultValue = "PENDING_RECONCILIATION") String status,
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "50") int limit) {
        String p = periodOrCurrent(period);
        GatewayAttemptStatus st;
        try {
            st = GatewayAttemptStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown attempt status: " + status);
        }
        return ApiResponse.ok(new GatewayBudgetDtos.AttemptPage(
                metering.listAttempts(st, p, limit).stream().map(GatewayBudgetController::toAttempt).toList()));
    }

    @PostMapping(ApiPaths.GATEWAY_ADMIN + "/reconciliations")
    public ApiResponse<GatewayBudgetDtos.AttemptLine> reconcile(
            @RequestBody GatewayBudgetDtos.ReconcileRequest body) {
        requireBody(body);
        if (body.attemptId() == null || body.attemptId().isBlank()) {
            throw new IllegalArgumentException("attemptId is required");
        }
        if (body.actualTokens() < 0) {
            throw new IllegalArgumentException("actualTokens must be >= 0");
        }
        return ApiResponse.ok(toAttempt(metering.settle(body.attemptId(), body.actualTokens())));
    }

    @GetMapping(ApiPaths.GATEWAY_ADMIN + "/projections")
    public ApiResponse<GatewayBudgetDtos.ProjectionPage> adminProjections(
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "50") int limit) {
        String p = periodOrCurrent(period);
        return ApiResponse.ok(new GatewayBudgetDtos.ProjectionPage(
                metering.listProjections(p, limit).stream().map(GatewayBudgetController::toProjection).toList()));
    }

    @GetMapping(ApiPaths.GATEWAY_ADMIN + "/policy")
    public ApiResponse<GatewayBudgetDtos.Policy> getPolicy() {
        return ApiResponse.ok(toPolicy(policy.get()));
    }

    @PutMapping(ApiPaths.GATEWAY_ADMIN + "/policy")
    public ApiResponse<GatewayBudgetDtos.Policy> putPolicy(@RequestBody GatewayBudgetDtos.PolicyUpdate body) {
        requireBody(body);
        GatewayReportMode mode = null;
        if (body.reportMode() != null && !body.reportMode().isBlank()) {
            try {
                mode = GatewayReportMode.valueOf(body.reportMode().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "reportMode must be LEGACY, SHADOW, or GATEWAY_PRIMARY");
            }
        }
        GatewayPolicy updated = policy.update(
                body.pendingAlertAfterHours(),
                body.pendingCompanyTakeoverAfterBusinessDays(),
                body.reconReserveTargetTokens(),
                body.defaultFloatAllowance(),
                mode,
                body.managedClis());
        String period = GatewayPeriods.current(clock);
        // Keep company float pool usable when a default F is configured (debit only if the pool row exists).
        ensureFloatPoolAtLeast(period, updated.defaultFloatAllowance());
        // Transfer UNALLOCATED → RECON toward target (ledgered; never REPLACE/mint).
        metering.fundReconReserveToTarget(period, updated.reconReserveTargetTokens());
        return ApiResponse.ok(toPolicy(updated));
    }

    /**
     * After allocate: explicit F wins; otherwise apply company default when the employee still has F=0.
     */
    private GatewayAccount applyFloatAfterAllocate(String period, String userId, Long requestedF,
                                                   GatewayAccount allocated) {
        if (requestedF != null) {
            if (requestedF < 0) {
                throw new IllegalArgumentException("floatAllowance must be >= 0");
            }
            GatewayAccount withF = metering.setFloatAllowance(period, userId, requestedF);
            ensureFloatPoolAtLeast(period, requestedF);
            return withF;
        }
        long def = policy.get().defaultFloatAllowance();
        if (def > 0 && allocated.floatAllowance() == 0) {
            GatewayAccount withF = metering.setFloatAllowance(period, userId, def);
            ensureFloatPoolAtLeast(period, def);
            return withF;
        }
        return allocated;
    }

    /** Seed or raise FLOAT_POOL so a claim of F does not fail for want of company float cash. */
    private void ensureFloatPoolAtLeast(String period, long minAvailable) {
        if (minAvailable <= 0) {
            return;
        }
        long current = metering.findAccount(period, GatewayAccountType.FLOAT_POOL, "")
                .map(GatewayAccount::available)
                .orElse(0L);
        if (current < minAvailable) {
            metering.putPool(period, GatewayAccountType.FLOAT_POOL, minAvailable);
        }
    }

    private String periodOrCurrent(String period) {
        return period == null || period.isBlank() ? GatewayPeriods.current(clock) : period;
    }

    private static GatewayBudgetDtos.Policy toPolicy(GatewayPolicy p) {
        return new GatewayBudgetDtos.Policy(
                p.pendingAlertAfterHours(),
                p.pendingCompanyTakeoverAfterBusinessDays(),
                p.reconReserveTargetTokens(),
                p.defaultFloatAllowance(),
                p.reportMode().name(),
                p.managedClis());
    }

    private static void requireBody(Object body) {
        if (body == null) {
            throw new IllegalArgumentException("body is required");
        }
    }

    private static GatewayBudgetDtos.Balance toBalance(GatewayAccount a) {
        return new GatewayBudgetDtos.Balance(a.period(), a.owner(), a.type().name(), a.available(), a.reserved(),
                a.settled(), a.baseQuota(), a.floatAllowance(), a.floatTurnId());
    }

    private static GatewayBudgetDtos.LedgerLine toLine(GatewayLedgerEntry e) {
        return new GatewayBudgetDtos.LedgerLine(e.operationId(), e.entryNo(), e.period(), e.accountType().name(),
                e.ownerId(), e.deltaAvailable(), e.deltaReserved(), e.deltaSettled(), e.reason());
    }

    private static GatewayBudgetDtos.AttemptLine toAttempt(GatewayAttempt a) {
        return new GatewayBudgetDtos.AttemptLine(a.id(), a.turnId(), a.runId(), a.payerUserId(), a.cli(), a.model(),
                a.period(), a.reservedTokens(), a.actualTokens(), a.status().name());
    }

    private static GatewayBudgetDtos.ProjectionLine toProjection(GatewayProjectionEvent e) {
        return new GatewayBudgetDtos.ProjectionLine(e.id(), e.attemptId(), e.eventType(), e.lineNo(),
                e.payerUserId(), e.cli(), e.model(), e.actualTokens());
    }
}