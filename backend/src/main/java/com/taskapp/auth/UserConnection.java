package com.taskapp.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * An external sign-in account connected to a user (table {@code user_connections}, V7). Unique per provider and
 * provider account id ({@code sub}), and per user and provider.
 */
@Entity
@Table(name = "user_connections")
public class UserConnection {

    /** The only provider so far; the value stored in {@code provider} (CHECK constraint in V7). */
    public static final String GOOGLE = "google";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(nullable = false, updatable = false, length = 20)
    private String provider;

    /** The provider's stable account id (OpenID Connect {@code sub}), compared exactly. */
    @Column(name = "provider_subject", nullable = false, updatable = false, length = 255)
    private String providerSubject;

    /** The provider account's email as of its latest sign-in or connect (normalized), for display only. */
    @Column(name = "provider_email", nullable = false, length = 254)
    private String providerEmail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserConnection() {
    }

    public UserConnection(User user, String provider, String providerSubject, String providerEmail,
            Instant createdAt) {
        this.user = user;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.providerEmail = providerEmail;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public String getProviderEmail() {
        return providerEmail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    void changeProviderEmail(String providerEmail) {
        this.providerEmail = providerEmail;
    }
}
