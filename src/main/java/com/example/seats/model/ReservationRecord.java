package com.example.seats.model;

import java.time.Instant;
import java.util.UUID;

public record ReservationRecord(
        UUID id,
        UUID showId,
        String userId,
        long amountPaise,
        String status,
        String idempotencyKey,
        String requestHash,
        Instant createdAt) {
}
