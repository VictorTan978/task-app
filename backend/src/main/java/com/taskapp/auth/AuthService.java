package com.taskapp.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskapp.auth.dto.LoginRequest;
import com.taskapp.auth.dto.RegisterRequest;
import com.taskapp.auth.dto.UserResponse;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    /** Compared against when the email is unknown so both failure paths cost one BCrypt check. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    /** Request fields are already normalized by {@link RegisterRequest}. */
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailTakenException();
        }
        User user = new User(request.email(), passwordEncoder.encode(request.password()), request.displayName());
        try {
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // Concurrent registration with the same email hit the unique constraint.
            throw new EmailTakenException();
        }
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
