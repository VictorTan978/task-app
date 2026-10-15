package com.taskapp.auth;

import com.taskapp.auth.dto.UserResponse;

/**
 * Result of connecting Google to the signed-in account ({@link GoogleAccountService#connect}).
 *
 * @param user the updated account; null unless connected
 */
public record GoogleConnect(Outcome outcome, UserResponse user) {

    public enum Outcome {
        /** Connected now. */
        CONNECTED,
        /** This Google account was already connected to this user (still a success). */
        ALREADY_CONNECTED,
        /** This Google account is connected to another user (AC13). */
        IN_USE,
        /** This user already has a different Google account connected. */
        OTHER_GOOGLE_CONNECTED,
        /** The ID token lacks a usable {@code sub} or email. */
        INVALID,
        /** The signed-in user no longer exists. */
        USER_GONE;

        public boolean connected() {
            return this == CONNECTED || this == ALREADY_CONNECTED;
        }
    }
}
