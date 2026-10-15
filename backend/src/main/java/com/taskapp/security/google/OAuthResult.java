package com.taskapp.security.google;

/**
 * Where the Google flow sends the browser at the end: a frontend route plus at most one {@code oauth} query value
 * (contract 0.8.0, schema {@code OAuthResult} and the redirect table in "Sign in with Google").
 */
enum OAuthResult {
    /** Login OK. No query parameter. */
    LOGGED_IN("/tasks"),
    GOOGLE_FAILED("/login?oauth=google_failed"),
    GOOGLE_UNVERIFIED("/login?oauth=google_unverified"),
    GOOGLE_CONNECTED("/account?oauth=google_connected"),
    GOOGLE_IN_USE("/account?oauth=google_in_use"),
    GOOGLE_CONNECT_FAILED("/account?oauth=google_connect_failed"),
    /** The account is disabled (contract 0.9.0), on login and on connect. */
    ACCOUNT_DISABLED("/login?oauth=account_disabled");

    private final String route;

    OAuthResult(String route) {
        this.route = route;
    }

    String route() {
        return route;
    }
}
