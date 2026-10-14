package com.taskapp.auth;

/** What an emailed link token is for (column {@code email_tokens.purpose}). */
public enum EmailTokenPurpose {
    VERIFY_EMAIL,
    RESET_PASSWORD
}
