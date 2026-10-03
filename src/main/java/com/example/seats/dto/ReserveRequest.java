package com.example.seats.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Note: there is deliberately no user_id field. Identity comes only from the auth token. */
public record ReserveRequest(
        List<String> seats,
        @JsonProperty("idempotency_key") String idempotencyKey) {
}
