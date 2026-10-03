package com.example.seats.service.impl;

import com.example.seats.dto.ReservationResponse;
import com.example.seats.dto.ReserveRequest;
import com.example.seats.dto.ReserveResult;
import com.example.seats.exception.ApiException;
import com.example.seats.exception.DeclineException;
import com.example.seats.metrics.ReservationMetrics;
import com.example.seats.model.ReservationRecord;
import com.example.seats.model.ShowRecord;
import com.example.seats.repository.ReservationRepository;
import com.example.seats.repository.SeatRepository;
import com.example.seats.repository.ShowRepository;
import com.example.seats.repository.UserShowCountRepository;
import com.example.seats.service.ReservationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * All reservation business rules live here.
 *
 * Behaviour (documented contract):
 *  - Multi-seat requests are ALL-OR-NOTHING: either every requested seat is confirmed to the caller in one
 *    transaction, or none is (409 SEAT_TAKEN) and nothing is changed.
 *  - Lock order inside the reserve transaction is always:  reservation row -> per-user counter row -> seats in
 *    ascending label order.  A single global order means no wait cycle, hence no deadlock.
 *  - Idempotency: UNIQUE (user_id, idempotency_key) on reservations + request hash. Same key + same body
 *    replays the original (HTTP 200); same key + different body is 409 IDEMPOTENCY_KEY_REUSED.
 *  - A declined request rolls back completely, so it leaves no key behind and may be retried.
 */
@Service
public class ReservationServiceImpl implements ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationServiceImpl.class);
    private static final int MAX_SEATS_PER_REQUEST = 100;
    private static final int MAX_KEY_LENGTH = 255;
    private static final int MAX_ATTEMPTS = 4;

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final UserShowCountRepository counts;
    private final TransactionTemplate tx;
    private final ReservationMetrics metrics;

    public ReservationServiceImpl(ShowRepository shows,
                                  SeatRepository seats,
                                  ReservationRepository reservations,
                                  UserShowCountRepository counts,
                                  TransactionTemplate tx,
                                  ReservationMetrics metrics) {
        this.shows = shows;
        this.seats = seats;
        this.reservations = reservations;
        this.counts = counts;
        this.tx = tx;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ reserve

    @Override
    public ReserveResult reserve(String userId, UUID showId, ReserveRequest request, String idempotencyKeyHeader) {
        try {
            ReserveResult result = doReserve(userId, showId, request, idempotencyKeyHeader);
            if (result.replay()) {
                metrics.replayed();
            } else {
                metrics.confirmed();
            }
            return result;
        } catch (DeclineException e) {
            metrics.declined(e.reason());
            throw e;
        }
    }

    private ReserveResult doReserve(String userId, UUID showId, ReserveRequest request, String headerKey) {
        if (request == null) {
            throw ApiException.badRequest("INVALID_REQUEST", "request body is required");
        }
        List<String> labels = normalizeSeats(request.seats());
        String key = resolveKey(headerKey, request.idempotencyKey());
        String hash = requestHash(showId, labels);

        ShowRecord show = shows.findById(showId)
                .orElseThrow(() -> ApiException.notFound("SHOW_NOT_FOUND", "show not found"));

        // 1) Idempotent replay (read-only). Must come first: a retried success must replay, not 409.
        Optional<ReservationRecord> prior = reservations.findByUserAndKey(userId, key);
        if (prior.isPresent()) {
            return replay(prior.get(), hash);
        }

        // 2) Existence check + cheap fast-path decline. This is only an optimisation that keeps a hot-seat
        //    stampede from queueing on row locks; the atomic UPDATE below remains the sole authority.
        Map<String, String> statuses = seats.statuses(showId, labels);
        if (statuses.size() != labels.size()) {
            throw ApiException.badRequest("INVALID_SEAT", "one or more seats do not exist in this show");
        }
        List<String> taken = new ArrayList<>();
        for (String label : labels) {
            if (!"available".equals(statuses.get(label))) {
                taken.add(label);
            }
        }
        if (!taken.isEmpty()) {
            // the original request with this key may have committed since step 1; if so this is a replay
            Optional<ReservationRecord> late = reservations.findByUserAndKey(userId, key);
            if (late.isPresent()) {
                return replay(late.get(), hash);
            }
            throw DeclineException.seatTaken(taken);
        }

        // 3) The atomic decision, in one transaction (retried only on lock failures such as deadlock).
        return inTx(() -> reserveInTransaction(userId, show, labels, key, hash));
    }

    private ReserveResult reserveInTransaction(String userId, ShowRecord show, List<String> labels,
                                               String key, String hash) {
        UUID reservationId = UUID.randomUUID();
        long amount = Math.multiplyExact(show.pricePaise(), (long) labels.size());

        // exactly-once: UNIQUE (user_id, idempotency_key). 0 rows => a committed reservation already has this key.
        int inserted = reservations.insertIfAbsent(reservationId, show.id(), userId, amount, key, hash);
        if (inserted == 0) {
            ReservationRecord existing = reservations.findByUserAndKey(userId, key)
                    .orElseThrow(() -> new IllegalStateException("idempotency row vanished"));
            return replay(existing, hash);
        }

        // per-user limit: guarded UPDATE on the user's counter row (serialises this user's concurrent requests)
        counts.ensureRow(show.id(), userId);
        if (counts.tryIncrement(show.id(), userId, labels.size(), show.perUserLimit()) == 0) {
            throw DeclineException.perUserLimit(show.perUserLimit());
        }

        // seats, in ascending label order (labels are pre-sorted): conditional UPDATE per seat.
        for (String label : labels) {
            if (seats.claim(show.id(), label, userId, reservationId) == 0) {
                // throwing rolls back everything above: reservation row, counter, and earlier seats
                throw DeclineException.seatTaken(List.of(label));
            }
        }
        reservations.insertSeats(reservationId, labels);

        return new ReserveResult(
                new ReservationResponse(reservationId, show.id(), userId, labels, amount, "confirmed"), false);
    }

    private ReserveResult replay(ReservationRecord existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw DeclineException.keyReused();
        }
        List<String> labels = reservations.findSeats(existing.id());
        return new ReserveResult(toResponse(existing, labels), true);
    }

    // ------------------------------------------------------------------ cancel

    @Override
    public ReservationResponse cancel(String userId, UUID reservationId) {
        ReservationRecord r = reservations.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND", "reservation not found"));
        if (!r.userId().equals(userId)) {
            throw ApiException.forbidden("only the owner can cancel a reservation");
        }

        Boolean changed = inTx(() -> {
            // guarded on owner AND status='confirmed'; the row lock makes concurrent cancels run one at a time
            if (reservations.markCancelled(reservationId, userId) == 0) {
                return false; // already cancelled: nothing to release
            }
            int n = reservations.countSeats(reservationId);
            counts.decrement(r.showId(), userId, n);
            // guarded on reservation_id: can never touch a seat that belongs to someone else
            seats.releaseByReservation(reservationId);
            return true;
        });
        if (Boolean.TRUE.equals(changed)) {
            metrics.cancelled();
        }

        ReservationRecord fresh = reservations.findById(reservationId).orElse(r);
        return toResponse(fresh, reservations.findSeats(reservationId));
    }

    // ------------------------------------------------------------------ helpers

    /** Runs work in a transaction; retries only on lock-acquisition failures (deadlock / lock timeout). */
    private <T> T inTx(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> work.get());
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("lock failure on attempt {}, retrying: {}", attempt, e.toString());
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5L, 25L) * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static ReservationResponse toResponse(ReservationRecord r, List<String> labels) {
        return new ReservationResponse(r.id(), r.showId(), r.userId(), labels, r.amountPaise(), r.status());
    }

    private static List<String> normalizeSeats(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("INVALID_REQUEST", "seats must be a non-empty list");
        }
        if (raw.size() > MAX_SEATS_PER_REQUEST) {
            throw ApiException.badRequest("INVALID_REQUEST", "too many seats in one request (max " + MAX_SEATS_PER_REQUEST + ")");
        }
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            String label = s == null ? "" : s.trim();
            if (label.isEmpty()) {
                throw ApiException.badRequest("INVALID_REQUEST", "seat labels must be non-empty");
            }
            if (!seen.add(label)) {
                throw ApiException.badRequest("INVALID_REQUEST", "duplicate seat in request: " + label);
            }
            out.add(label);
        }
        Collections.sort(out); // deterministic lock order for every transaction
        return out;
    }

    /** Header wins over body. If the client sent no key, the request simply gets a fresh unique one. */
    private static String resolveKey(String headerKey, String bodyKey) {
        String key = headerKey != null && !headerKey.isBlank() ? headerKey.trim()
                : (bodyKey != null && !bodyKey.isBlank() ? bodyKey.trim() : null);
        if (key == null) {
            return "auto-" + UUID.randomUUID();
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw ApiException.badRequest("INVALID_REQUEST", "idempotency key too long (max " + MAX_KEY_LENGTH + ")");
        }
        return key;
    }

    private static String requestHash(UUID showId, List<String> sortedLabels) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((showId + "|" + String.join(",", sortedLabels)).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
