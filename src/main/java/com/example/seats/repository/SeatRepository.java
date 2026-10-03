package com.example.seats.repository;

import com.example.seats.dto.SeatView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SeatRepository {

    private final JdbcTemplate jdbc;

    public SeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Bulk-insert every seat of a new show as 'available' (single statement). */
    public int insertAll(UUID showId, List<String> labels) {
        return jdbc.update(Sql.stmt(
                "INSERT INTO seats(show_id, label) SELECT ?::uuid, unnest(?::text[])",
                showId, labels.toArray(new String[0])));
    }

    /** Non-locking read used only as a fast path / existence check: label -> status. */
    public Map<String, String> statuses(UUID showId, List<String> labels) {
        RowMapper<Map.Entry<String, String>> mapper =
                (rs, i) -> Map.entry(rs.getString(1), rs.getString(2));
        List<Map.Entry<String, String>> rows = jdbc.query(Sql.stmt(
                "SELECT label, status FROM seats WHERE show_id = ?::uuid AND label = ANY(?::text[])",
                showId, labels.toArray(new String[0])), mapper);
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : rows) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * THE atomic decision. A conditional UPDATE guarded on current state: the row lock serialises
     * competing writers and the loser re-evaluates "status = 'available'" after the winner commits,
     * so it updates 0 rows. Returns 1 if this caller won the seat, 0 otherwise.
     */
    public int claim(UUID showId, String label, String userId, UUID reservationId) {
        return jdbc.update(
                "UPDATE seats SET status = 'confirmed', user_id = ?, reservation_id = ? "
                        + "WHERE show_id = ? AND label = ? AND status = 'available'",
                userId, reservationId, showId, label);
    }

    /**
     * Release the seats of ONE reservation. Guarded on reservation_id, so it can never free (or
     * resurrect) a seat that now belongs to somebody else.
     */
    public int releaseByReservation(UUID reservationId) {
        return jdbc.update(
                "UPDATE seats SET status = 'available', user_id = NULL, reservation_id = NULL "
                        + "WHERE reservation_id = ?",
                reservationId);
    }

    public List<SeatView> listSeats(UUID showId) {
        return jdbc.query(
                "SELECT label, status FROM seats WHERE show_id = ? ORDER BY label",
                (rs, i) -> new SeatView(rs.getString(1), rs.getString(2)),
                showId);
    }

    public Map<String, Long> countByStatus(UUID showId) {
        RowMapper<Map.Entry<String, Long>> mapper =
                (rs, i) -> Map.entry(rs.getString(1), rs.getLong(2));
        List<Map.Entry<String, Long>> rows = jdbc.query(
                "SELECT status, count(*) FROM seats WHERE show_id = ? GROUP BY status", mapper, showId);
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, Long> e : rows) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
