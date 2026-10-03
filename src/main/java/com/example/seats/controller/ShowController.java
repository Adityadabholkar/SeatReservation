package com.example.seats.controller;

import com.example.seats.dto.CreateShowRequest;
import com.example.seats.dto.ShowResponse;
import com.example.seats.service.ShowService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShowController {

    private final ShowService shows;

    public ShowController(ShowService shows) {
        this.shows = shows;
    }

    /** Admin only (enforced by AdminAuthInterceptor). */
    @PostMapping("/shows")
    public ResponseEntity<ShowResponse> create(@RequestBody(required = false) CreateShowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shows.create(request));
    }

    /** ?seats=false returns counts only (cheap for very large halls). */
    @GetMapping("/shows/{id}")
    public ShowResponse get(@PathVariable("id") UUID id,
                            @RequestParam(name = "seats", defaultValue = "true") boolean includeSeats) {
        return shows.get(id, includeSeats);
    }
}
