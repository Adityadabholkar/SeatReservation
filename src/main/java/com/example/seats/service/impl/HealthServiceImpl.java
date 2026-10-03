package com.example.seats.service.impl;

import com.example.seats.service.HealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthServiceImpl implements HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthServiceImpl.class);

    private final JdbcTemplate healthJdbc;

    public HealthServiceImpl(@Qualifier("healthJdbcTemplate") JdbcTemplate healthJdbc) {
        this.healthJdbc = healthJdbc;
    }

    @Override
    public boolean isDatabaseReachable() {
        try {
            Integer one = healthJdbc.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (Exception e) {
            log.warn("readiness: database check failed: {}", e.toString());
            return false; // fail closed
        }
    }
}
