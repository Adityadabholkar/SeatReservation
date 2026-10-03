package com.example.seats.web;

import com.example.seats.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Guards POST /shows: requires the admin token. */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final String adminToken;

    public AdminAuthInterceptor(@Value("${app.admin-token}") String adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!"POST".equalsIgnoreCase(req.getMethod())) {
            return true;
        }
        String token = Bearer.extract(req);
        if (token == null) {
            throw ApiException.unauthorized("missing bearer token");
        }
        if (!Bearer.constantTimeEquals(adminToken, token)) {
            throw ApiException.forbidden("admin token required");
        }
        return true;
    }
}
