package com.example.seats.exception;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A business decline (HTTP 409). Carries the metric reason label. */
public class DeclineException extends ApiException {

    public static final String SEAT_TAKEN = "seat_taken";
    public static final String PER_USER_LIMIT = "per_user_limit";
    public static final String IDEMPOTENT_REPLAY = "idempotent_replay";
    public static final String IDEMPOTENCY_KEY_REUSED = "idempotency_key_reused";

    private final String reason;

    private DeclineException(String code, String message, String reason, Map<String, Object> details) {
        super(HttpStatus.CONFLICT, code, message, details);
        this.reason = reason;
    }

    public String reason() { return reason; }

    public static DeclineException seatTaken(List<String> seats) {
        return new DeclineException("SEAT_TAKEN", "one or more requested seats are already taken",
                SEAT_TAKEN, Map.of("seats", seats));
    }

    public static DeclineException perUserLimit(int limit) {
        return new DeclineException("PER_USER_LIMIT_EXCEEDED",
                "per-user seat limit of " + limit + " would be exceeded",
                PER_USER_LIMIT, Map.of("per_user_limit", limit));
    }

    public static DeclineException keyReused() {
        return new DeclineException("IDEMPOTENCY_KEY_REUSED",
                "this idempotency key was already used with a different request body",
                IDEMPOTENCY_KEY_REUSED, Map.of());
    }
}
