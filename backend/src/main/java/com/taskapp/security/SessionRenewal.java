package com.taskapp.security;

import java.time.Instant;

/**
 * "End all my other sessions, keep this one" in progress (log out other sessions, password change). Created by
 * {@link SessionAuthenticator#keepOnlyThisSession}.
 *
 * @param previousValidFrom this session's validity time before the renewal; the service rejects the action (401)
 *                          if the session was already ended by a cutoff later than this
 * @param now               the new cutoff: {@code sessions_valid_after} and this session's new validity time
 * @param keepSeries        this device's remember-me login, which is kept; null if this is a normal session
 */
public record SessionRenewal(Instant previousValidFrom, Instant now, String keepSeries) {
}
