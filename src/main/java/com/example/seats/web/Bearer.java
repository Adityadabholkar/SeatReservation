package com.example.seats.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class Bearer {

    private Bearer() {}

    /** Returns the bearer token, or null if the header is missing/malformed. */
    static String extract(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null) return null;
        h = h.trim();
        if (h.length() < 8 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = h.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
