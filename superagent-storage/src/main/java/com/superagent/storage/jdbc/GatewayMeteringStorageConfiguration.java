package com.superagent.storage.jdbc;

import com.superagent.core.gateway.GatewayMetering;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link GatewayMetering} ledger onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class GatewayMeteringStorageConfiguration {

    @Bean
    GatewayMetering jdbcGatewayMetering(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcGatewayMetering(jdbc);
    }
}
