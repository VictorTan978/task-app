package com.taskapp.security.audit;

/**
 * Writes the admin audit lines (specs/roles-admin.md AC17), e.g.
 * <pre>SECURITY event=ADMIN_USER_DISABLED actor_id=1 target_id=42 action=disable email=admin@example.com ip=... path=...</pre>
 * {@code email} is always the acting admin's. Never contains a secret.
 */
public final class AdminAudit {

    private AdminAudit() {
    }

    /** A change that happened (call after it committed). {@code extra} are more name/value pairs, e.g. from/to. */
    public static void done(SecurityEvent event, AuditOrigin origin, String actorEmail, long actorId, long targetId,
            AdminAction action, String... extra) {
        String[] fields = new String[6 + extra.length];
        fields[0] = "actor_id";
        fields[1] = Long.toString(actorId);
        fields[2] = "target_id";
        fields[3] = Long.toString(targetId);
        fields[4] = "action";
        fields[5] = action.logValue();
        System.arraycopy(extra, 0, fields, 6, extra.length);
        SecurityEventLogger.log(event, origin, actorEmail, fields);
    }

    /**
     * A refused admin request: {@code ADMIN_ACCESS_DENIED}. {@code actorId}, {@code targetId} and {@code action} may be
     * null (written as {@code -}).
     */
    public static void denied(AuditOrigin origin, String actorEmail, Long actorId, String targetId, AdminAction action,
            String reason) {
        SecurityEventLogger.log(SecurityEvent.ADMIN_ACCESS_DENIED, origin, actorEmail,
                "actor_id", actorId == null ? null : actorId.toString(),
                "target_id", targetId,
                "action", action == null ? null : action.logValue(),
                "reason", reason);
    }
}
