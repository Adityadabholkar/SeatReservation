package com.example.seats.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-show gauges read straight from the database at scrape time (via the isolated health pool),
 * so they always reconcile with the API state:
 *   seats_available{show_id}, seats_held{show_id}, seats_confirmed{show_id}
 */
@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);
    private static final String[] STATUSES = {"available", "held", "confirmed"};

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();

    public SeatGauges(MeterRegistry registry, @Qualifier("healthJdbcTemplate") JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerExistingShows() {
        try {
            List<UUID> ids = jdbc.query("SELECT id FROM shows", (rs, i) -> rs.getObject(1, UUID.class));
            ids.forEach(this::register);
            log.info("registered seat gauges for {} existing shows", ids.size());
        } catch (Exception e) {
            log.warn("could not pre-register seat gauges: {}", e.toString());
        }
    }

    public void register(UUID showId) {
        if (!registered.add(showId)) {
            return;
        }
        for (String status : STATUSES) {
            Gauge.builder("seats." + status, () -> count(showId, status))
                    .description("Seats currently " + status)
                    .tag("show_id", showId.toString())
                    .strongReference(true)
                    .register(registry);
        }
    }

    private Number count(UUID showId, String status) {
        try {
            Long v = jdbc.queryForObject(
                    "SELECT count(*) FROM seats WHERE show_id = ? AND status = ?", Long.class, showId, status);
            if (v == null) {
                return Double.NaN;
            }
            return v;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
