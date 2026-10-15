package com.taskapp.auth;

/**
 * Login with the correct password, but the account is disabled (specs/roles-admin.md AC18): 403
 * {@code ACCOUNT_DISABLED}. Only thrown after the password matched, so it never reveals whether an account exists.
 */
public class AccountDisabledException extends RuntimeException {

    private final transient String email;

    public AccountDisabledException(String email) {
        super("This account has been disabled.", null, false, false);
        this.email = email;
    }

    /** The normalized email the login was attempted with (for the security log). */
    public String email() {
        return email;
    }
}
