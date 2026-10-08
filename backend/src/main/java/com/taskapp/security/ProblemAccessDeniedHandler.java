package com.taskapp.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import com.taskapp.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 403 problem+json: CSRF_INVALID for missing/wrong CSRF token, FORBIDDEN otherwise. */
class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter writer;

    ProblemAccessDeniedHandler(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        if (accessDeniedException instanceof CsrfException) {
            writer.write(request, response, HttpStatus.FORBIDDEN, ErrorCode.CSRF_INVALID,
                    "Invalid or missing CSRF token");
        } else {
            writer.write(request, response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Access denied");
        }
    }
}
