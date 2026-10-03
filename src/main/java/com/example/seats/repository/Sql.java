package com.example.seats.repository;

import java.sql.PreparedStatement;
import org.springframework.jdbc.core.PreparedStatementCreator;

/** Small helper that binds String[] parameters as Postgres text[] and everything else via setObject. */
final class Sql {

    private Sql() {}

    static PreparedStatementCreator stmt(String sql, Object... params) {
        return con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p instanceof String[] arr) {
                    ps.setArray(i + 1, con.createArrayOf("text", arr));
                } else {
                    ps.setObject(i + 1, p);
                }
            }
            return ps;
        };
    }
}
