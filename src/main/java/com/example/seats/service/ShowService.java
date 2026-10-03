package com.example.seats.service;

import com.example.seats.dto.CreateShowRequest;
import com.example.seats.dto.ShowResponse;
import java.util.UUID;

public interface ShowService {

    /** Creates a show and all of its seats (all 'available'). */
    ShowResponse create(CreateShowRequest request);

    /** Consistent snapshot of a show: per-seat status (optional) and counts. */
    ShowResponse get(UUID showId, boolean includeSeats);
}
