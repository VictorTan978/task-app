package com.taskapp.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 60)
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

    protected User() {
    }

    public User(String email, String passwordHash, String displayName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
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
