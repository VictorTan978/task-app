package com.taskapp.security.google;

import java.util.function.Supplier;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * The Google client registration, built on first use from the issuer's OpenID configuration (discovery), so the
 * app starts even when Google is unreachable. A failed load is not cached: the next flow tries again (the per-IP
 * start limit bounds how often). Never log the registration: its {@code toString()} contains the client secret.
 */
final class LazyClientRegistrationRepository implements ClientRegistrationRepository {

    private final String registrationId;
    private final Supplier<ClientRegistration> loader;
    private volatile ClientRegistration registration;

    LazyClientRegistrationRepository(String registrationId, Supplier<ClientRegistration> loader) {
        this.registrationId = registrationId;
        this.loader = loader;
    }

    /**
     * The registration for {@code id}, or null for any other id.
     *
     * @throws RuntimeException if the issuer's configuration can't be loaded (Google unreachable, bad response)
     */
    @Override
    public ClientRegistration findByRegistrationId(String id) {
        if (!registrationId.equals(id)) {
            return null;
        }
        ClientRegistration loaded = registration;
        if (loaded == null) {
            synchronized (this) {
                loaded = registration;
                if (loaded == null) {
                    loaded = loader.get();
                    registration = loaded;
                }
            }
        }
        return loaded;
    }
}
