package com.example.seats.metrics;

import com.example.seats.exception.DeclineException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Prometheus names:
 *   reservations_confirmed_total
 *   reservations_declined_total{reason="seat_taken|per_user_limit|idempotent_replay|idempotency_key_reused"}
 *   reservations_cancelled_total
 */
@Component
public class ReservationMetrics {

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final Counter cancelled;
    private final Map<String, Counter> declined = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations confirmed").register(registry);
        this.cancelled = Counter.builder("reservations.cancelled")
                .description("Reservations cancelled by their owner").register(registry);
        // pre-register so every reason shows up at 0 before the first event
        declinedCounter(DeclineException.SEAT_TAKEN);
        declinedCounter(DeclineException.PER_USER_LIMIT);
        declinedCounter(DeclineException.IDEMPOTENT_REPLAY);
        declinedCounter(DeclineException.IDEMPOTENCY_KEY_REUSED);
    }

    private Counter declinedCounter(String reason) {
        return declined.computeIfAbsent(reason, r -> Counter.builder("reservations.declined")
                .description("Reservation requests that did not create a new reservation, by reason")
                .tag("reason", r).register(registry));
    }

    public void confirmed() { confirmed.increment(); }

    public void cancelled() { cancelled.increment(); }

    public void declined(String reason) { declinedCounter(reason).increment(); }

    public void replayed() { declinedCounter(DeclineException.IDEMPOTENT_REPLAY).increment(); }
}
