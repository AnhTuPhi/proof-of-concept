package com.vndirect.kstreams.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record User(
        String userId,
        String displayName,
        String tier,
        String country
) {
    @JsonCreator
    public User(
            @JsonProperty("userId") String userId,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("tier") String tier,
            @JsonProperty("country") String country
    ) {
        this.userId = userId;
        this.displayName = displayName;
        this.tier = tier;
        this.country = country;
    }
}
