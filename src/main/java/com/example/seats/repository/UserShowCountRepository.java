package com.example.seats.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserShowCountRepository {

    private final JdbcTemplate jdbc;

    public UserShowCountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureRow(UUID showId, String userId) {
        jdbc.update(
                "INSERT INTO user_show_counts(show_id, user_id, held_count) VALUES (?, ?, 0) "
                        + "ON CONFLICT (show_id, user_id) DO NOTHING",
                showId, userId);
    }

    /**
     * Atomic, guarded increment. The UPDATE takes the row lock, so one user's concurrent requests
     * are serialised here and the limit check can never be raced. Returns 0 if over the limit.
     */
    public int tryIncrement(UUID showId, String userId, int n, int limit) {
        return jdbc.update(
                "UPDATE user_show_counts SET held_count = held_count + ? "
                        + "WHERE show_id = ? AND user_id = ? AND held_count + ? <= ?",
                n, showId, userId, n, limit);
    }

    public void decrement(UUID showId, String userId, int n) {
        jdbc.update(
                "UPDATE user_show_counts SET held_count = GREATEST(held_count - ?, 0) "
                        + "WHERE show_id = ? AND user_id = ?",
                n, showId, userId);
    }
}
