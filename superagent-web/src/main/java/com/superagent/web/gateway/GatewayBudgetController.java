package com.superagent.web.gateway;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.gateway.GatewayBudgetDtos;
import com.superagent.core.gateway.GatewayAccount;
import com.superagent.core.gateway.GatewayAccountType;
import com.superagent.core.gateway.GatewayLedgerEntry;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayPeriods;
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

/**
 * Session-authenticated budget APIs for the model gateway (#34 PR-05).
 * Model data plane stays on {@link GatewayController} with run tokens.
 */
@RestController
public class GatewayBudgetController {

    private final GatewayMetering metering;
    private final Clock clock;

    public GatewayBudgetController(GatewayMetering metering, Clock clock) {
        this.metering = metering;
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
        return ApiResponse.ok(toBalance(
                metering.allocate(body.operationId(), period, body.userId(), body.amount(), auth.getName())));
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

    @GetMapping(ApiPaths.GATEWAY_ADMIN + "/balances")
    public ApiResponse<GatewayBudgetDtos.Balance> adminEmployeeBalance(
            @RequestParam String userId,
            @RequestParam(required = false) String period) {
        String p = periodOrCurrent(period);
        GatewayAccount a = metering.findEmployee(p, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到员工额度账户"));
        return ApiResponse.ok(toBalance(a));
    }

    private String periodOrCurrent(String period) {
        return period == null || period.isBlank() ? GatewayPeriods.current(clock) : period;
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
}