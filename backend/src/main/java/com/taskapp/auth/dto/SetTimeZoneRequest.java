package com.taskapp.auth.dto;

import com.taskapp.auth.validation.ValidTimeZone;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code PUT /api/account/time-zone} (contract 0.14.0). {@code timeZone}: an IANA region id; {@code ifUnset}: null or
 * false = always set, true = only if the account has no zone yet.
 */
public record SetTimeZoneRequest(
        @NotBlank(message = "Time zone is required")
        @ValidTimeZone
        String timeZone,

        Boolean ifUnset) {

    public boolean onlyIfUnset() {
        return Boolean.TRUE.equals(ifUnset);
    }
}
