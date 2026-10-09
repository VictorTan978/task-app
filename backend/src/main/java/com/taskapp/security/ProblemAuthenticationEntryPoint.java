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

/** 401 problem+json for unauthenticated access. Never redirects. */
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponseWriter writer;

    ProblemAuthenticationEntryPoint(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        SecurityEventLogger.log(SecurityEvent.UNAUTHENTICATED, request, null);
        writer.write(request, response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Authentication required");
    }
}
