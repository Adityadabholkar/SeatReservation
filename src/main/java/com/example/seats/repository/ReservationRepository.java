package com.example.seats.repository;

import com.example.seats.model.ReservationRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private static final RowMapper<ReservationRecord> MAPPER = (rs, i) -> new ReservationRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("show_id", UUID.class),
            rs.getString("user_id"),
            rs.getLong("amount_paise"),
            rs.getString("status"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Exactly-once enforcement: UNIQUE (user_id, idempotency_key). Returns 1 if this call created the
     * reservation, 0 if a committed reservation with the same key already exists. A concurrent
     * in-flight insert of the same key makes this statement wait for that transaction to finish.
     */
    public int insertIfAbsent(UUID id, UUID showId, String userId, long amountPaise, String key, String hash) {
        return jdbc.update(
                "INSERT INTO reservations(id, show_id, user_id, amount_paise, status, idempotency_key, request_hash) "
                        + "VALUES (?, ?, ?, ?, 'confirmed', ?, ?) "
                        + "ON CONFLICT (user_id, idempotency_key) DO NOTHING",
                id, showId, userId, amountPaise, key, hash);
    }

    public void insertSeats(UUID reservationId, List<String> labels) {
        jdbc.update(Sql.stmt(
                "INSERT INTO reservation_seats(reservation_id, label) SELECT ?::uuid, unnest(?::text[])",
                reservationId, labels.toArray(new String[0])));
    }

    public Optional<ReservationRecord> findByUserAndKey(String userId, String key) {
        List<ReservationRecord> rows = jdbc.query(
                "SELECT * FROM reservations WHERE user_id = ? AND idempotency_key = ?", MAPPER, userId, key);
        return rows.stream().findFirst();
    }

    public Optional<ReservationRecord> findById(UUID id) {
        List<ReservationRecord> rows = jdbc.query("SELECT * FROM reservations WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<String> findSeats(UUID reservationId) {
        return jdbc.query(
                "SELECT label FROM reservation_seats WHERE reservation_id = ? ORDER BY label",
                (rs, i) -> rs.getString(1),
                reservationId);
    }

    public int countSeats(UUID reservationId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM reservation_seats WHERE reservation_id = ?", Long.class, reservationId);
        return n == null ? 0 : n.intValue();
    }

    /** Guarded on owner AND current status: only the owner can cancel, and only once. */
    public int markCancelled(UUID id, String userId) {
        return jdbc.update(
                "UPDATE reservations SET status = 'cancelled', cancelled_at = now() "
                        + "WHERE id = ? AND user_id = ? AND status = 'confirmed'",
                id, userId);
    }
}
