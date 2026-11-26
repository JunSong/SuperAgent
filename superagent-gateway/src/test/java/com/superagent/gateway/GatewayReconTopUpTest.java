package com.superagent.gateway;

import com.superagent.core.gateway.GatewayMetering;
import com.superagent.core.gateway.GatewayPolicy;
import com.superagent.core.gateway.GatewayReportMode;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.platform.tenant.CompanyService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Scheduled RECON top-up respects company isolation, target, and empty UNALLOCATED. */
class GatewayReconTopUpTest {

    @Test
    void tickMovesWhenUnallocatedCoversTarget() {
        CompanyService companies = mock(CompanyService.class);
        doAnswer(inv -> {
            Runnable work = inv.getArgument(0);
            TenantContext.run("co-a", work);
            TenantContext.run("co-b", work);
            return null;
        }).when(companies).forEach(any());

        AtomicLong moved = new AtomicLong();
        GatewayMetering metering = mock(GatewayMetering.class);
        when(metering.fundReconReserveToTarget(anyString(), anyLong())).thenAnswer(inv -> {
            long target = inv.getArgument(1);
            moved.addAndGet(target);
            return target;
        });

        SystemSettingStore settings = mock(SystemSettingStore.class);
        when(settings.all()).thenReturn(java.util.Map.of(
                "gateway.recon-reserve.target-tokens", "1000"));
        GatewayPolicyService policy = new GatewayPolicyService(settings);
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);

        GatewayReconTopUp job = new GatewayReconTopUp(companies, policy, metering, clock, true, 60);
        assertThat(job.tickOnce()).isEqualTo(2);
        assertThat(moved.get()).isEqualTo(2000);
    }

    @Test
    void tickSkipsWhenTargetIsZero() {
        CompanyService companies = mock(CompanyService.class);
        doAnswer(inv -> {
            TenantContext.run("co-a", (Runnable) inv.getArgument(0));
            return null;
        }).when(companies).forEach(any());
        GatewayMetering metering = mock(GatewayMetering.class);
        GatewayPolicyService policy = mock(GatewayPolicyService.class);
        when(policy.get()).thenReturn(new GatewayPolicy(1, 2, 0, 0, GatewayReportMode.LEGACY, List.of(), List.of()));

        GatewayReconTopUp job = new GatewayReconTopUp(companies, policy, metering,
                Clock.systemUTC(), true, 60);
        assertThat(job.tickOnce()).isZero();
        verify(metering, never()).fundReconReserveToTarget(anyString(), anyLong());
    }

    @Test
    void tickCountsOnlyCompaniesThatMoved() {
        List<String> seen = new ArrayList<>();
        CompanyService companies = mock(CompanyService.class);
        doAnswer(inv -> {
            Runnable work = inv.getArgument(0);
            TenantContext.run("rich", work);
            TenantContext.run("poor", work);
            return null;
        }).when(companies).forEach(any());
        GatewayMetering metering = mock(GatewayMetering.class);
        when(metering.fundReconReserveToTarget(anyString(), anyLong())).thenAnswer(inv -> {
            String company = TenantContext.require();
            seen.add(company);
            return "rich".equals(company) ? 50L : 0L;
        });
        GatewayPolicyService policy = mock(GatewayPolicyService.class);
        when(policy.get()).thenReturn(new GatewayPolicy(1, 2, 500, 0, GatewayReportMode.LEGACY, List.of(), List.of()));

        GatewayReconTopUp job = new GatewayReconTopUp(companies, policy, metering,
                Clock.systemUTC(), true, 60);
        assertThat(job.tickOnce()).isEqualTo(1);
        assertThat(seen).containsExactly("rich", "poor");
    }
}
