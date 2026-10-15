package com.taskapp.auth.dto;

/** {@code GET /api/auth/providers}: which external sign-in providers are configured on the server. */
public record ProvidersResponse(boolean google) {
}
