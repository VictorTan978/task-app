package com.taskapp.auth;

import com.taskapp.auth.dto.UserResponse;

/**
 * Result of signing in with Google ({@link GoogleAccountService#signIn}).
 *
 * @param user   the account signed in to; null unless {@link Outcome#signsIn()}
 * @param reason why nothing was signed in, for the security log; null on success
 */
public record GoogleLogin(Outcome outcome, UserResponse user, String reason) {

    public enum Outcome {
        /** Matched by the Google account id ({@code sub}). */
        EXISTING,
        /** Matched by a verified email and connected now (AC5). */
        LINKED,
        /** A new account was created and connected (AC2). */
        CREATED,
        /** An account exists for the email but can't be linked, or the email isn't verified: nothing changed. */
        UNVERIFIED,
        /** The ID token lacks a usable {@code sub} or email: nothing changed. */
        INVALID;

        public boolean signsIn() {
            return this == EXISTING || this == LINKED || this == CREATED;
        }
    }

    static GoogleLogin signedIn(Outcome outcome, UserResponse user) {
        return new GoogleLogin(outcome, user, null);
    }

    static GoogleLogin refused(Outcome outcome, String reason) {
        return new GoogleLogin(outcome, null, reason);
    }
}
