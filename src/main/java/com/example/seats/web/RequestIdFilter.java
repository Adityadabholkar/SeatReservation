package com.example.seats.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Correlation id per request (X-Request-Id in/out, put in the logging MDC) + one structured access-log line. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,100}$");

    private final boolean logRequests;

    public RequestIdFilter(@Value("${app.log-requests:true}") boolean logRequests) {
        this.logRequests = logRequests;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader("X-Request-Id");
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put("requestId", id);
        res.setHeader("X-Request-Id", id);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            if (logRequests) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                Object user = req.getAttribute(AuthAttributes.USER_ID);
                var event = log.atInfo()
                        .setMessage("http_request")
                        .addKeyValue("method", req.getMethod())
                        .addKeyValue("path", req.getRequestURI())
                        .addKeyValue("status", res.getStatus())
                        .addKeyValue("duration_ms", ms);
                if (user != null) {
                    event = event.addKeyValue("user_id", user.toString());
                }
                event.log();
            }
            MDC.remove("requestId");
        }
    }
}
