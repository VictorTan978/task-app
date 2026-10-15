package com.taskapp.auth.dto;

import com.taskapp.auth.UserConnection;

/**
 * An external sign-in account connected to the user ({@code Connection} in the contract). Never carries the
 * provider's account id or any token.
 *
 * @param email the provider account's email as of its latest sign-in or connect, for display only
 */
public record ConnectionResponse(String provider, String email) {

    public static ConnectionResponse from(UserConnection connection) {
        return new ConnectionResponse(connection.getProvider(), connection.getProviderEmail());
    }
}
