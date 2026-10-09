package com.taskapp.security.ratelimit;

import java.util.function.Consumer;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-client-IP limit for one auth endpoint. Runs before the request body is read and validated, so
 * invalid requests count too. A {@link RateLimitedException} thrown here is rendered by the MVC exception
 * handler like any other error.
 */
class AuthRateLimitInterceptor implements HandlerInterceptor {

    private final Consumer<String> acquireForIp;

    AuthRateLimitInterceptor(Consumer<String> acquireForIp) {
        this.acquireForIp = acquireForIp;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("POST".equals(request.getMethod())) {
            acquireForIp.accept(clientIp(request));
        }
        return true;
    }

    /**
     * The client address. With {@code server.forward-headers-strategy: native} (application.yml) Tomcat
     * rewrites it to the real client from {@code X-Forwarded-For} when the direct peer is a trusted proxy
     * ({@code TRUSTED_PROXIES}); from any other peer it stays the peer address, so the header can't be spoofed.
     */
    static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }
}
