package com.taskapp.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
