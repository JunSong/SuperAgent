package com.superagent.gateway;

import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayPeriods;
import com.superagent.platform.tenant.CompanyService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mid-month RECON top-up: periodically move UNALLOCATED → RECON_RESERVE up to the company target
 * (#34 backlog; never mints). One tick per enabled company via {@link CompanyService#forEach}.
 */
@Component
public class GatewayReconTopUp {

    private static final Logger log = LoggerFactory.getLogger(GatewayReconTopUp.class);

    private final CompanyService companies;
    private final GatewayPolicyService policy;
    private final GatewayMetering metering;
    private final Clock clock;
    private final boolean enabled;
    private final long intervalMinutes;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gateway-recon-topup");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public GatewayReconTopUp(
            CompanyService companies,
            GatewayPolicyService policy,
            GatewayMetering metering,
            Clock clock,
            @Value("${superagent.gateway.recon-topup.enabled:true}") boolean enabled,
            @Value("${superagent.gateway.recon-topup.interval-minutes:60}") long intervalMinutes) {
        this.companies = companies;
        this.policy = policy;
        this.metering = metering;
        this.clock = clock;
        this.enabled = enabled;
        this.intervalMinutes = Math.max(1, intervalMinutes);
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!enabled) {
            log.info("gateway RECON top-up disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::tickSafely, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("gateway RECON top-up started (interval={}m)", intervalMinutes);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /** Visible for tests. */
    int tickOnce() {
        int[] movedCompanies = {0};
        companies.forEach(() -> {
            if (topUpCurrentCompany() > 0) {
                movedCompanies[0]++;
            }
        });
        return movedCompanies[0];
    }

    private void tickSafely() {
        try {
            tickOnce();
        } catch (RuntimeException e) {
            log.warn("gateway RECON top-up tick failed: {}", e.getMessage());
        }
    }

    private long topUpCurrentCompany() {
        long target = policy.get().reconReserveTargetTokens();
        if (target <= 0) {
            return 0L;
        }
        String period = GatewayPeriods.current(clock);
        long moved = metering.fundReconReserveToTarget(period, target);
        if (moved > 0) {
            log.info("gateway RECON top-up period={} moved={}", period, moved);
        }
        return moved;
    }
}
