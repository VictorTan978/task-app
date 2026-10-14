package com.taskapp.security;

/**
 * HTTP session attributes written at sign-in by {@link SessionAuthenticator} and checked on every request by
 * {@link SessionUserRefreshFilter}. A signed-in session missing {@link #EXPIRES_AT} or {@link #VALID_FROM} is
 * treated as ended.
 */
public final class SessionAttributes {

    /** {@link java.time.Instant}: the session ends at this time however busy it is (maximum length). */
    public static final String EXPIRES_AT = "expiresAt";
    /**
     * {@link java.time.Instant}: the session is ended when the user's {@code sessions_valid_after} is later than this
     * (log out other sessions, password change, stolen remember-me cookie). The sign-in time, moved forward when
     * this session itself ends the others.
     */
    public static final String VALID_FROM = "validFrom";
    /** String id of this device's remember-me login (base64url of its series hash), or absent for normal sessions. */
    public static final String REMEMBER_ME_SERIES = "rememberMeSeries";

    private SessionAttributes() {
    }
}
