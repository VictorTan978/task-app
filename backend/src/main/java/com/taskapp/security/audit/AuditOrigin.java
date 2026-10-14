package com.taskapp.security.audit;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The client IP and request path of a security log line, copied from the request so that a background job (e.g.
 * sending an email after the response) can still log where the action came from. The request object itself must
 * not be used after the response.
 */
public record AuditOrigin(String ip, String path) {

    /** No request (ip and path are logged as {@code -}). */
    public static final AuditOrigin NONE = new AuditOrigin(null, null);

    public static AuditOrigin of(HttpServletRequest request) {
        return request == null ? NONE : new AuditOrigin(request.getRemoteAddr(), SecurityEventLogger.path(request));
    }

    /** The origin of the request this thread is handling, or {@link #NONE} outside a request. */
    public static AuditOrigin current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return of(servlet.getRequest());
        }
        return NONE;
    }
}
