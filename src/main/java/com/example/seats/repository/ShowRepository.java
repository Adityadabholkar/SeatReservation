package com.example.seats.repository;

import com.example.seats.model.ShowRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

    private static final RowMapper<ShowRecord> MAPPER = (rs, i) -> new ShowRecord(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getLong("price_paise"),
            rs.getInt("per_user_limit"),
            rs.getInt("total_seats"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ShowRecord s) {
        jdbc.update(
                "INSERT INTO shows(id, name, price_paise, per_user_limit, total_seats) VALUES (?, ?, ?, ?, ?)",
                s.id(), s.name(), s.pricePaise(), s.perUserLimit(), s.totalSeats());
    }

    public Optional<ShowRecord> findById(UUID id) {
        List<ShowRecord> rows = jdbc.query("SELECT * FROM shows WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }
}
