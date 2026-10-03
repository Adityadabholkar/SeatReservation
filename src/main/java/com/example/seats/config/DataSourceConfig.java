package com.example.seats.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two connection pools:
 *  - "main": all business traffic. Large connection-timeout so a burst QUEUES instead of failing (no 5xx).
 *  - "health": tiny, isolated pool for readiness + metric gauges, so probes still work while main is saturated.
 *
 * Accepts either a JDBC url (DB_URL / DB_USER / DB_PASSWORD) or a platform-style DATABASE_URL
 * (postgres://user:pass@host:port/db), which is what Render/Railway/Neon hand out.
 */
@Configuration
public class DataSourceConfig {

    private final Environment env;

    public DataSourceConfig(Environment env) {
        this.env = env;
    }

    private record Conn(String jdbcUrl, String user, String password) {}

    private Conn resolve() {
        String raw = env.getProperty("DATABASE_URL");
        if (raw != null && !raw.isBlank()) {
            if (raw.startsWith("jdbc:")) {
                return new Conn(raw, env.getProperty("DB_USER", "seats"), env.getProperty("DB_PASSWORD", "seats"));
            }
            URI uri = URI.create(raw.replaceFirst("^postgres(ql)?://", "http://"));
            String userInfo = uri.getUserInfo();
            String user = null;
            String pass = null;
            if (userInfo != null) {
                int idx = userInfo.indexOf(':');
                user = idx >= 0 ? userInfo.substring(0, idx) : userInfo;
                pass = idx >= 0 ? userInfo.substring(idx + 1) : null;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String url = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath()
                    + (uri.getQuery() != null ? "?" + uri.getQuery() : "");
            return new Conn(url, user, pass);
        }
        return new Conn(
                env.getProperty("DB_URL", "jdbc:postgresql://localhost:5432/seats"),
                env.getProperty("DB_USER", "seats"),
                env.getProperty("DB_PASSWORD", "seats"));
    }

    private HikariConfig base(String poolName) {
        Conn c = resolve();
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(poolName);
        cfg.setJdbcUrl(c.jdbcUrl());
        if (c.user() != null) cfg.setUsername(c.user());
        if (c.password() != null) cfg.setPassword(c.password());
        cfg.setMaxLifetime(25 * 60 * 1000L);
        cfg.setKeepaliveTime(5 * 60 * 1000L);
        return cfg;
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        int size = env.getProperty("DB_POOL_SIZE", Integer.class, 20);
        long timeoutMs = env.getProperty("DB_CONNECTION_TIMEOUT_MS", Long.class, 120_000L);
        HikariConfig cfg = base("main");
        cfg.setMaximumPoolSize(size);
        cfg.setMinimumIdle(Math.min(5, size));
        cfg.setConnectionTimeout(timeoutMs);
        return new HikariDataSource(cfg);
    }

    @Bean(name = "healthDataSource")
    public DataSource healthDataSource() {
        HikariConfig cfg = base("health");
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(2_000);
        cfg.setValidationTimeout(1_000);
        cfg.setInitializationFailTimeout(-1); // never block/abort startup if the DB is briefly away
        return new HikariDataSource(cfg);
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "healthJdbcTemplate")
    public JdbcTemplate healthJdbcTemplate(@Qualifier("healthDataSource") DataSource ds) {
        JdbcTemplate t = new JdbcTemplate(ds);
        t.setQueryTimeout(5);
        return t;
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }
}
