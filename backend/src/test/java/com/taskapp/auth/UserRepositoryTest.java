package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.auth.dto.RegisterRequest;

/**
 * Runs against MySQL 8.4 with the real Flyway migrations: the derived queries compare with the V4 collation
 * (utf8mb4_0900_ai_ci), and a duplicate insert produces the real Hibernate/Connector-J exception chain.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String HASH = "$2a$10$" + "x".repeat(53);

    @Autowired
    UserRepository repository;

    @BeforeEach
    void seed() {
        repository.saveAndFlush(new User("jose@example.com", HASH, "José"));
    }

    @Test
    void existsByEmailIgnoresCaseAndAccents() {
        assertThat(repository.existsByEmail("jose@example.com")).isTrue();
        assertThat(repository.existsByEmail("josé@example.com")).isTrue();
        assertThat(repository.findByEmail("josé@example.com")).get()
                .extracting(User::getEmail).isEqualTo("jose@example.com");
        assertThat(repository.existsByEmail("josh@example.com")).isFalse();
    }

    @Test
    void existsByDisplayNameIgnoresCaseAndAccents() {
        assertThat(repository.existsByDisplayName("José")).isTrue();
        assertThat(repository.existsByDisplayName("jose")).isTrue();
        assertThat(repository.existsByDisplayName("JOSE")).isTrue();
        assertThat(repository.existsByDisplayName("Josie")).isFalse();
    }

    /** Simulates losing the race: the pre-checks see nothing, then the insert hits a unique key. */
    private AuthService serviceWhosePreChecksMissTheConflict() {
        UserRepository racing = mock(UserRepository.class, delegatesTo(repository));
        doReturn(false).when(racing).existsByEmail(anyString());
        doReturn(false).when(racing).existsByDisplayName(anyString());
        return new AuthService(racing, new BCryptPasswordEncoder(4));
    }

    @Test
    void displayNameUniqueKeyViolationMapsToDisplayNameTaken() {
        AuthService service = serviceWhosePreChecksMissTheConflict();

        assertThatThrownBy(() -> service.register(new RegisterRequest("JOSE", "other@example.com", "correct-horse")))
                .isInstanceOfSatisfying(RegistrationConflictException.class, e -> {
                    assertThat(e.displayNameTaken()).isTrue();
                    assertThat(e.emailTaken()).isFalse();
                });
    }

    @Test
    void emailUniqueKeyViolationMapsToEmailTaken() {
        AuthService service = serviceWhosePreChecksMissTheConflict();

        assertThatThrownBy(() -> service.register(new RegisterRequest("Someone Else", "josé@example.com", "correct-horse")))
                .isInstanceOfSatisfying(RegistrationConflictException.class, e -> {
                    assertThat(e.emailTaken()).isTrue();
                    assertThat(e.displayNameTaken()).isFalse();
                });
    }
}
