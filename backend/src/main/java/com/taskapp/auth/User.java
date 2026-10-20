package com.taskapp.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.taskapp.security.Role;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    /** BCrypt hash; null = no password (account created with Google, specs/google-sign-in.md). */
    @Column(name = "password_hash", length = 60)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Sessions valid from before this time are ended on their next request; null = no cutoff. */
    @Column(name = "sessions_valid_after")
    private Instant sessionsValidAfter;

    /** When the user confirmed their email (link or password reset by email); null = not confirmed. */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** USER, ADMIN or SUPER_ADMIN (V8); new accounts are USER. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 16)
    private Role role = Role.USER;

    /** When an admin disabled the account (V8); null = active. */
    @Column(name = "disabled_at")
    private Instant disabledAt;

    /** IANA region id (V12); null until set, then reminders are computed in UTC. */
    @Column(name = "time_zone", length = 64)
    private String timeZone;

    /** Whether a fired reminder also sends an email (V12; the in-app notification is always created). */
    @Column(name = "reminder_emails", nullable = false)
    private boolean reminderEmails = true;

    /**
     * Connected external sign-in accounts (V7). Rows are written through {@link UserConnectionRepository}. Deleting
     * the user deletes them first by primary key (REMOVE cascade, needed once they are loaded; the database's
     * {@code ON DELETE CASCADE} covers every other delete). Loaded lazily, so read it inside a transaction (or on a
     * user created in this request).
     */
    @OneToMany(mappedBy = "user", cascade = CascadeType.REMOVE)
    @OrderBy("provider")
    private List<UserConnection> connections = new ArrayList<>();

    protected User() {
    }

    public User(String email, String passwordHash, String displayName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.createdAt = Instant.now();
    }

    /** An account created with Google: no password, email confirmed at {@code emailVerifiedAt}. */
    public static User withoutPassword(String email, String displayName, Instant emailVerifiedAt) {
        User user = new User(email, null, displayName);
        user.emailVerifiedAt = emailVerifiedAt;
        return user;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    /** Null when the account has no password ({@link #hasPassword()}). */
    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSessionsValidAfter() {
        return sessionsValidAfter;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    /** The connected external sign-in accounts, ordered by provider. */
    public List<UserConnection> getConnections() {
        return connections;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public boolean isReminderEmails() {
        return reminderEmails;
    }

    /** {@code timeZone} must already be a valid region id. */
    public void changeTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public void changeReminderEmails(boolean reminderEmails) {
        this.reminderEmails = reminderEmails;
    }

    public Role getRole() {
        return role;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public boolean isDisabled() {
        return disabledAt != null;
    }

    /** Disables the account at {@code at}. Callers also end its sessions and remember-me logins. */
    public void disable(Instant at) {
        this.disabledAt = at;
    }

    /** Makes the account usable again. Ended sessions stay ended. */
    public void enable() {
        this.disabledAt = null;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    /** Ends every session of this user that became valid before {@code cutoff} (see SessionUserRefreshFilter). */
    public void endSessionsBefore(Instant cutoff) {
        this.sessionsValidAfter = cutoff;
    }

    /** {@code displayName} must already be trimmed and validated. */
    public void changeDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /** {@code passwordHash} is the BCrypt hash, never the raw password. */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
