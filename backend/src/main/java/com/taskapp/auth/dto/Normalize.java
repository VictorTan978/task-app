package com.taskapp.auth.dto;

import java.util.Locale;

final class Normalize {

    private Normalize() {
    }

    static String email(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
