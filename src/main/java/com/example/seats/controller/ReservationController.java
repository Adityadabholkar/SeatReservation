package com.example.seats.controller;

import com.example.seats.dto.ReservationResponse;
import com.example.seats.dto.ReserveRequest;
import com.example.seats.dto.ReserveResult;
import com.example.seats.service.ReservationService;
import com.example.seats.web.AuthAttributes;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    /** 201 = newly created, 200 (+ Idempotent-Replay: true) = replay of an earlier identical request. */
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable("id") UUID showId,
            @RequestAttribute(AuthAttributes.USER_ID) String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ReserveRequest body) {
        ReserveResult result = reservations.reserve(userId, showId, body, idempotencyKey);
        if (result.replay()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.reservation());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.reservation());
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(
            @PathVariable("id") UUID reservationId,
            @RequestAttribute(AuthAttributes.USER_ID) String userId) {
        return reservations.cancel(userId, reservationId);
    }
}
