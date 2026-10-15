package com.taskapp.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.taskapp.common.error.ErrorCode;
import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 401 problem+json for unauthenticated access. Never redirects. {@code ACCOUNT_DISABLED} when this request's session
 * or remember-me login belonged to a disabled account (request attribute {@link #ACCOUNT_DISABLED}, set by
 * {@link SessionUserRefreshFilter} and {@link HashedRememberMeServices}), otherwise {@code UNAUTHENTICATED}.
 */
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** Request attribute: this request was signed in as an account that is disabled now. */
    static final String ACCOUNT_DISABLED = ProblemAuthenticationEntryPoint.class.getName() + ".ACCOUNT_DISABLED";
    static final String ACCOUNT_DISABLED_DETAIL = "This account has been disabled.";

    private final ProblemResponseWriter writer;

    ProblemAuthenticationEntryPoint(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        SecurityEventLogger.log(SecurityEvent.UNAUTHENTICATED, request, null);
        if (Boolean.TRUE.equals(request.getAttribute(ACCOUNT_DISABLED))) {
            writer.write(request, response, HttpStatus.UNAUTHORIZED, ErrorCode.ACCOUNT_DISABLED,
                    ACCOUNT_DISABLED_DETAIL);
            return;
        }
        writer.write(request, response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Authentication required");
    }
}
