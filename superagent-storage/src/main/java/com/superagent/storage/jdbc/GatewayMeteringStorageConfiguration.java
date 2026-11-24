package com.superagent.storage.jdbc;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the JDBC ledger. Concrete type so Spring can inject both
 * {@link com.superagent.core.gateway.GatewayMetering} and
 * {@link com.superagent.core.gateway.GatewaySettlementUsage}.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayMeteringStorageConfiguration {

    @Bean
    JdbcGatewayMetering jdbcGatewayMetering(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcGatewayMetering(jdbc);
    }
}
