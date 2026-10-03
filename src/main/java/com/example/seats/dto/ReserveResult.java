package com.example.seats.dto;

/** Internal result of a reserve call: the reservation plus whether it was an idempotent replay. */
public record ReserveResult(ReservationResponse reservation, boolean replay) {
}
