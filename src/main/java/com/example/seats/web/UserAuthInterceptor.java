package com.example.seats.web;

import com.example.seats.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards reserve / cancel. The bearer token IS the user identity (opaque, 1-64 chars of [A-Za-z0-9_.:@-]).
 * The resolved id is stored as a request attribute; nothing in a body or other header can override it.
 */
@Component
public class UserAuthInterceptor implements HandlerInterceptor {

    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_.:@-]{1,64}$");

    private final String adminToken;

    public UserAuthInterceptor(@Value("${app.admin-token}") String adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String token = Bearer.extract(req);
        if (token == null) {
            throw ApiException.unauthorized("missing bearer token");
        }
        if (Bearer.constantTimeEquals(adminToken, token)) {
            throw ApiException.forbidden("the admin token cannot act as a user");
        }
        if (!TOKEN.matcher(token).matches()) {
            throw ApiException.unauthorized("malformed token");
        }
        req.setAttribute(AuthAttributes.USER_ID, token);
        return true;
    }
}
