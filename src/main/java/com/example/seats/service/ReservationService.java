package com.example.seats.service;

import com.example.seats.dto.ReservationResponse;
import com.example.seats.dto.ReserveRequest;
import com.example.seats.dto.ReserveResult;
import java.util.UUID;

public interface ReservationService {

    /**
     * Reserve seats for a user (all-or-nothing).
     *
     * @param userId           identity taken from the auth token (never from the body)
     * @param idempotencyKeyHeader value of the Idempotency-Key header, may be null (body key is then used)
     */
    ReserveResult reserve(String userId, UUID showId, ReserveRequest request, String idempotencyKeyHeader);

    /** Cancel a reservation. Only the owner may cancel; repeated cancels are harmless. */
    ReservationResponse cancel(String userId, UUID reservationId);
}
