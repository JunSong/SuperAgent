package com.superagent.gateway;

import com.superagent.core.gateway.GatewayAccount;
import com.superagent.core.gateway.GatewayAttempt;
import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayQuotaBusyException;
import com.superagent.core.gateway.GatewayQuotaExhaustedException;
import com.superagent.core.gateway.GatewayQuotaInsufficientException;
import com.superagent.core.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Short wait when available is zero but in-flight reserves may free enough room (#34 QUOTA_BUSY).
 * At most two waiters per employee; wait up to 2s then fail busy.
 */
@Component
public class QuotaReserveGate {

    static final int MAX_WAITERS = 2;
    static final Duration DEFAULT_WAIT = Duration.ofSeconds(2);
    static final Duration DEFAULT_POLL = Duration.ofMillis(50);

    private final Clock clock;
    private final ConcurrentHashMap<String, AtomicInteger> waiters = new ConcurrentHashMap<>();
    private volatile Duration waitTimeout = DEFAULT_WAIT;
    private volatile Duration pollInterval = DEFAULT_POLL;

    public QuotaReserveGate(Clock clock) {
        this.clock = clock;
    }

    /** Test hook: shorten waits. */
    void setTiming(Duration waitTimeout, Duration pollInterval) {
        this.waitTimeout = waitTimeout;
        this.pollInterval = pollInterval;
    }

    GatewayAttempt reserve(GatewayMetering metering, GatewayMetering.ReserveRequest request) {
        try {
            return metering.reserve(request);
        } catch (GatewayQuotaInsufficientException first) {
            if (!mayBecomeAvailable(metering, request)) {
                throw first;
            }
            return waitAndRetry(metering, request, first);
        }
    }

    private GatewayAttempt waitAndRetry(GatewayMetering metering, GatewayMetering.ReserveRequest request,
                                        GatewayQuotaInsufficientException first) {
        String key = waiterKey(request);
        AtomicInteger counter = waiters.computeIfAbsent(key, k -> new AtomicInteger());
        if (counter.incrementAndGet() > MAX_WAITERS) {
            counter.decrementAndGet();
            throw new GatewayQuotaBusyException(
                    "额度暂时被其他请求占用，请稍后重试（QUOTA_BUSY）");
        }
        try {
            Instant deadline = clock.instant().plus(waitTimeout);
            while (clock.instant().isBefore(deadline)) {
                sleep(pollInterval);
                try {
                    return metering.reserve(request);
                } catch (GatewayQuotaExhaustedException exhausted) {
                    throw exhausted;
                } catch (GatewayQuotaInsufficientException again) {
                    if (!mayBecomeAvailable(metering, request)) {
                        throw again;
                    }
                }
            }
            throw new GatewayQuotaBusyException(
                    "等待在途额度释放超时（QUOTA_BUSY）");
        } finally {
            counter.decrementAndGet();
        }
    }

    /**
     * Wait only when available is empty, something is held, and freeing the hold could cover R.
     */
    private static boolean mayBecomeAvailable(GatewayMetering metering, GatewayMetering.ReserveRequest request) {
        Optional<GatewayAccount> account = metering.findEmployee(request.period(), request.payerUserId());
        if (account.isEmpty()) {
            return false;
        }
        GatewayAccount a = account.get();
        return a.available() == 0 && a.reserved() >= request.reservedTokens();
    }

    private static String waiterKey(GatewayMetering.ReserveRequest request) {
        return TenantContext.require() + "|" + request.period() + "|" + request.payerUserId();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(Math.max(1, d.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayQuotaBusyException("等待额度时被中断（QUOTA_BUSY）");
        }
    }
}
