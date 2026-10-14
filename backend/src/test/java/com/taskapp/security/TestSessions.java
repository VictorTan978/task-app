package com.taskapp.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.core.Authentication;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * MockMvc sign-in for controller slices: spring-security-test's {@code authentication(...)} (context stored in
 * the mock HTTP session) plus the lifetime attributes a real sign-in writes, so
 * {@link SessionUserRefreshFilter} accepts the session. A session without them is treated as ended (401).
 */
public final class TestSessions {

    private TestSessions() {
    }

    public static RequestPostProcessor signedInSession(Authentication authentication) {
        RequestPostProcessor base = SecurityMockMvcRequestPostProcessors.authentication(authentication);
        return request -> {
            var processed = base.postProcessRequest(request);
            var session = processed.getSession(true);
            Instant now = Instant.now();
            // A session given with .session(...) keeps the attributes the test set.
            if (session.getAttribute(SessionAttributes.EXPIRES_AT) == null) {
                session.setAttribute(SessionAttributes.EXPIRES_AT, now.plus(Duration.ofHours(1)));
            }
            if (session.getAttribute(SessionAttributes.VALID_FROM) == null) {
                session.setAttribute(SessionAttributes.VALID_FROM, now.minus(Duration.ofMinutes(1)));
            }
            return processed;
        };
    }
}
