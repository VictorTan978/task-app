package com.taskapp.security.audit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The admin endpoints (specs/roles-admin.md), as written in the {@code action} field of the admin audit lines. One
 * place, so the service and the security chain's access-denied handler name them the same way.
 */
public enum AdminAction {
    LIST("list"),
    DISABLE("disable"),
    ENABLE("enable"),
    REVOKE_SESSIONS("revoke_sessions"),
    CHANGE_ROLE("change_role"),
    DELETE("delete");

    public static final String PATH_PREFIX = "/api/admin/";

    private static final Pattern USER_PATH = Pattern.compile("^/api/admin/users/([^/]+)(?:/([^/]+))?/?$");

    private final String logValue;

    AdminAction(String logValue) {
        this.logValue = logValue;
    }

    public String logValue() {
        return logValue;
    }

    /** Whether the request is for an admin endpoint ({@code /api/admin/**}). */
    public static boolean isAdminPath(HttpServletRequest request) {
        String path = SecurityEventLogger.path(request);
        return path != null && path.startsWith(PATH_PREFIX);
    }

    /** The action an admin request asks for, or null if the path/method is not one of the admin endpoints. */
    public static AdminAction of(HttpServletRequest request) {
        String path = SecurityEventLogger.path(request);
        String method = request.getMethod();
        if (path == null || method == null) {
            return null;
        }
        if (path.equals("/api/admin/users") || path.equals("/api/admin/users/")) {
            return "GET".equals(method) ? LIST : null;
        }
        Matcher m = USER_PATH.matcher(path);
        if (!m.matches()) {
            return null;
        }
        String sub = m.group(2);
        if (sub == null) {
            return "DELETE".equals(method) ? DELETE : null;
        }
        return switch (method + " " + sub) {
            case "POST disable" -> DISABLE;
            case "POST enable" -> ENABLE;
            case "POST revoke-sessions" -> REVOKE_SESSIONS;
            case "PUT role" -> CHANGE_ROLE;
            default -> null;
        };
    }

    /** The {@code {id}} of {@code /api/admin/users/{id}/...} as written in the request (logged escaped), or null. */
    public static String targetOf(HttpServletRequest request) {
        String path = SecurityEventLogger.path(request);
        if (path == null) {
            return null;
        }
        Matcher m = USER_PATH.matcher(path);
        return m.matches() ? m.group(1) : null;
    }
}
