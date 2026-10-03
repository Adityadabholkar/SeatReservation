package com.example.seats.service;

public interface HealthService {

    /** True only if the database answers a trivial query right now. */
    boolean isDatabaseReachable();
}
