package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;

import com.taskapp.auth.dto.RegisterRequest;
import com.taskapp.security.Role;
import com.taskapp.security.ratelimit.AuthRateLimiter;
import com.taskapp.task.ListService;

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
        return new AuthService(racing, new BCryptPasswordEncoder(4), mock(AuthRateLimiter.class), mock(EmailJobs.class),
                mock(ListService.class), TransactionOperations.withoutTransaction());
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

    @Test
    void existsByDisplayNameAndIdNotIgnoresTheGivenUserOnly() {
        long joseId = repository.findByEmail("jose@example.com").orElseThrow().getId();
        User other = repository.saveAndFlush(new User("other@example.com", HASH, "Other"));

        assertThat(repository.existsByDisplayNameAndIdNot("jose", joseId)).isFalse();
        assertThat(repository.existsByDisplayNameAndIdNot("JOSE", joseId)).isFalse();
        assertThat(repository.existsByDisplayNameAndIdNot("jose", other.getId())).isTrue();
        assertThat(repository.existsByDisplayNameAndIdNot("José", other.getId())).isTrue();
        assertThat(repository.existsByDisplayNameAndIdNot("Josefina", other.getId())).isFalse();
    }

    // ---- roles and admin users (specs/roles-admin.md): V8 and the admin queries ----

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    private User saved(String email, String name, String createdAt) {
        User user = repository.saveAndFlush(new User(email, HASH, name));
        jdbc.update("update users set created_at = ? where id = ?", Timestamp.from(Instant.parse(createdAt)),
                user.getId());
        return user;
    }

    private List<String> emails(Page<User> page) {
        return page.getContent().stream().map(User::getEmail).toList();
    }

    private Page<User> search(String text, int page, int size) {
        entityManager.clear();
        return repository.search(text == null ? null : UserRepository.containsPattern(text),
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    @Test
    void v8NewAndExistingRowsAreActiveUsers() {
        jdbc.update("insert into users (email, password_hash, display_name, created_at) values (?, ?, ?, now(6))",
                "raw@example.com", HASH, "Raw");

        assertThat(jdbc.queryForObject("select role from users where email = 'raw@example.com'", String.class))
                .isEqualTo("USER");
        assertThat(jdbc.queryForObject("select disabled_at from users where email = 'raw@example.com'",
                Timestamp.class)).isNull();
        User jose = repository.findByEmail("jose@example.com").orElseThrow();
        assertThat(jose.getRole()).isEqualTo(Role.USER);
        assertThat(jose.isDisabled()).isFalse();
    }

    @Test
    void v8RoleIsCheckedAndComparedExactly() {
        // Note: ascii_bin is a PAD SPACE collation, so the CHECK alone would accept a trailing space ('ADMIN ').
        for (String role : List.of("OWNER", "admin", "Admin", "SUPERADMIN", "")) {
            assertThatThrownBy(() -> jdbc.update("update users set role = ? where email = 'jose@example.com'", role))
                    .as(role).hasMessageContaining("ck_users_role");
        }
        jdbc.update("update users set role = 'SUPER_ADMIN' where email = 'jose@example.com'");
        assertThat(jdbc.queryForObject("select count(*) from users where role = 'super_admin'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where role = 'SUPER_ADMIN'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void roleAndDisabledAtRoundTrip() {
        User user = repository.findByEmail("jose@example.com").orElseThrow();
        Instant at = Instant.parse("2026-10-15T08:30:00.123456Z");
        user.changeRole(Role.SUPER_ADMIN);
        user.disable(at);
        repository.saveAndFlush(user);
        entityManager.clear();

        User again = repository.findById(user.getId()).orElseThrow();
        assertThat(again.getRole()).isEqualTo(Role.SUPER_ADMIN);
        assertThat(again.getDisabledAt()).isEqualTo(at);
        assertThat(jdbc.queryForObject("select role from users where id = ?", String.class, user.getId()))
                .isEqualTo("SUPER_ADMIN");
        assertThat(repository.existsByRole(Role.SUPER_ADMIN)).isTrue();
        assertThat(repository.existsByRole(Role.ADMIN)).isFalse();

        again.enable();
        repository.saveAndFlush(again);
        assertThat(jdbc.queryForObject("select disabled_at from users where id = ?", Timestamp.class, user.getId()))
                .isNull();
    }

    @Test
    void findAllByIdForUpdateReturnsTheExistingRowsInIdOrder() {
        long jose = repository.findByEmail("jose@example.com").orElseThrow().getId();
        long other = repository.saveAndFlush(new User("other@example.com", HASH, "Other")).getId();

        assertThat(repository.findAllByIdForUpdate(List.of(other, jose))).extracting(User::getId)
                .containsExactly(jose, other);
        assertThat(repository.findAllByIdForUpdate(List.of(jose, 999_999L))).extracting(User::getId)
                .containsExactly(jose);
    }

    @Test
    void searchMatchesEmailOrNameContainingTheTextIgnoringCaseAndAccents() {
        saved("ana@example.com", "Ana María", "2026-10-01T00:00:00Z");
        saved("maria@example.com", "Mary", "2026-10-02T00:00:00Z");
        saved("bob@example.com", "Bob", "2026-10-03T00:00:00Z");

        assertThat(emails(search("MARÍA", 0, 20))).containsExactly("maria@example.com", "ana@example.com");
        assertThat(emails(search("maria", 0, 20))).containsExactly("maria@example.com", "ana@example.com");
        assertThat(emails(search("JOSE", 0, 20))).containsExactly("jose@example.com");
        assertThat(emails(search("Jos", 0, 20))).containsExactly("jose@example.com");
        assertThat(emails(search("EXAMPLE.COM", 0, 20))).hasSize(4);
        assertThat(search("nobody", 0, 20).getTotalElements()).isZero();
        assertThat(search("nobody", 0, 20).getTotalPages()).isZero();
    }

    @Test
    void searchTreatsPercentUnderscoreAndTheEscapeCharacterLiterally() {
        saved("percent@example.com", "100% sure", "2026-10-01T00:00:00Z");
        saved("under_score@example.com", "Under", "2026-10-02T00:00:00Z");
        saved("bang@example.com", "Bang!", "2026-10-03T00:00:00Z");
        saved("plain@example.com", "Plain 100 x", "2026-10-04T00:00:00Z");

        assertThat(emails(search("%", 0, 20))).containsExactly("percent@example.com");
        assertThat(emails(search("0%", 0, 20))).containsExactly("percent@example.com");
        assertThat(emails(search("_", 0, 20))).containsExactly("under_score@example.com");
        assertThat(emails(search("!", 0, 20))).containsExactly("bang@example.com");
        assertThat(emails(search("100", 0, 20))).containsExactly("plain@example.com", "percent@example.com");
    }

    @Test
    void searchPagesNewestFirstThenByIdAndAPagePastTheEndIsEmptyWithRealTotals() {
        jdbc.update("update users set created_at = ? where email = 'jose@example.com'",
                Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
        saved("a@example.com", "A", "2026-10-01T00:00:00Z");
        saved("b@example.com", "B", "2026-10-03T00:00:00Z");
        saved("c@example.com", "C", "2026-10-03T00:00:00Z");
        saved("d@example.com", "D", "2026-10-02T00:00:00Z");

        Page<User> first = search(null, 0, 2);
        assertThat(emails(first)).containsExactly("c@example.com", "b@example.com");
        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(emails(search(null, 1, 2))).containsExactly("d@example.com", "a@example.com");
        assertThat(emails(search(null, 2, 2))).containsExactly("jose@example.com");

        Page<User> pastTheEnd = search(null, 7, 2);
        assertThat(pastTheEnd.getContent()).isEmpty();
        assertThat(pastTheEnd.getTotalElements()).isEqualTo(5);
        assertThat(pastTheEnd.getTotalPages()).isEqualTo(3);
    }
}
