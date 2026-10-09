package com.taskapp.auth;

import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskapp.auth.dto.LoginRequest;
import com.taskapp.auth.dto.RegisterRequest;
import com.taskapp.auth.dto.UserResponse;

@Service
public class AuthService {

    static final String UK_EMAIL = "uk_users_email";
    static final String UK_DISPLAY_NAME = "uk_users_display_name";
    private static final Pattern MYSQL_DUPLICATE_KEY = Pattern.compile("for key '([^']+)'\\s*$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    /** Compared against when the email is unknown so both failure paths cost one BCrypt check. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    /**
     * Request fields are already normalized by {@link RegisterRequest}. Both uniqueness checks run so the
     * caller can report every taken field at once; the DB unique keys still catch concurrent registrations.
     */
    public UserResponse register(RegisterRequest request) {
        boolean emailTaken = userRepository.existsByEmail(request.email());
        boolean displayNameTaken = userRepository.existsByDisplayName(request.displayName());
        if (emailTaken || displayNameTaken) {
            throw new RegistrationConflictException(emailTaken, displayNameTaken);
        }
        User user = new User(request.email(), passwordEncoder.encode(request.password()), request.displayName());
        try {
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // A concurrent registration won the race; the violated unique key tells which field.
            throw conflictFor(e);
        }
    }

    /** Maps a unique-key violation on {@code users} by constraint name; anything else is rethrown as is. */
    static RuntimeException conflictFor(DataIntegrityViolationException e) {
        String constraint = violatedConstraint(e);
        if (UK_DISPLAY_NAME.equals(constraint)) {
            return new RegistrationConflictException(false, true);
        }
        if (UK_EMAIL.equals(constraint)) {
            return new RegistrationConflictException(true, false);
        }
        return e;
    }

    /**
     * The violated constraint name, lowercased and without a {@code table.} prefix, or null if unknown.
     * Uses Hibernate's extracted name when present, otherwise parses MySQL's
     * {@code Duplicate entry '...' for key 'users.uk_users_display_name'} (the key is the last quoted
     * part, so a user value containing a constraint name cannot be mistaken for it).
     */
    private static String violatedConstraint(Throwable e) {
        String fromMessage = null;
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                return normalizeConstraint(cve.getConstraintName());
            }
            if (fromMessage == null && t instanceof SQLException && t.getMessage() != null) {
                Matcher m = MYSQL_DUPLICATE_KEY.matcher(t.getMessage());
                if (m.find()) {
                    fromMessage = normalizeConstraint(m.group(1));
                }
            }
        }
        return fromMessage;
    }

    private static String normalizeConstraint(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.substring(lower.lastIndexOf('.') + 1);
    }

    /** Throws {@link InvalidCredentialsException} for unknown email and wrong password alike. */
    @Transactional(readOnly = true)
    public UserResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(long id) {
        return userRepository.findById(id).map(UserResponse::from).orElseThrow(UnauthenticatedException::new);
    }
}
