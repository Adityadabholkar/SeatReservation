package com.example.seats.model;

import java.time.Instant;
import java.util.UUID;

public record ShowRecord(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Instant createdAt) {
}
