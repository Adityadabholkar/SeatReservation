package com.example.seats.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CreateShowRequest(
        String name,
        List<String> seats,
        @JsonProperty("price_paise") Long pricePaise,
        @JsonProperty("per_user_limit") Integer perUserLimit) {
}
