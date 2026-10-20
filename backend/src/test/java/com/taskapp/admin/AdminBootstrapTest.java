package com.taskapp.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.auth.User;
import com.taskapp.auth.UserRepository;
import com.taskapp.security.Role;
import com.taskapp.security.audit.SecurityLogCapture;

import jakarta.persistence.EntityManager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** ADMIN_BOOTSTRAP_EMAIL (specs/roles-admin.md AC4), against MySQL 8.4 with the Flyway migrations. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminBootstrapTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String HASH = "$2a$10$" + "x".repeat(53);

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EntityManager entityManager;

    private final SecurityLogCapture audit = new SecurityLogCapture();
    private final ListAppender<ILoggingEvent> appLog = new ListAppender<>();
    private final Logger bootstrapLogger = (Logger) LoggerFactory.getLogger(AdminBootstrap.class);

    private long jose;

    @BeforeEach
    void start() {
        jose = users.saveAndFlush(new User("jose@example.com", HASH, "José")).getId();
        audit.start();
        appLog.start();
        bootstrapLogger.addAppender(appLog);
    }

    @AfterEach
    void stop() {
        audit.stop();
        bootstrapLogger.detachAppender(appLog);
        appLog.stop();
    }

    private void run(String setting) {
        new AdminBootstrap(users, transactionManager, setting).run(null);
    }

    private String role(long id) {
        return jdbc.queryForObject("select role from users where id = ?", String.class, id);
    }

    private List<String> warnings() {
        return appLog.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void emptySettingDoesNothing() {
        run("");
        run("   ");
        run(null);

        assertThat(role(jose)).isEqualTo("USER");
        assertThat(audit.lines()).isEmpty();
        assertThat(appLog.list).isEmpty();
    }

    @Test
    void existingAccountBecomesSuperAdminMatchedLikeLoginAndIsAudited() {
        run("  JOSÉ@Example.COM ");

        assertThat(role(jose)).isEqualTo("SUPER_ADMIN");
        assertThat(audit.lines()).containsExactly("SECURITY event=ADMIN_BOOTSTRAPPED target_id=" + jose
                + " email=jose@example.com ip=- path=-");
        assertThat(audit.events().get(0).getLevel().toString()).isEqualTo("INFO");
    }

    @Test
    void unknownEmailOnlyWarnsAndIsCheckedAgainOnTheNextStart() {
        run("nobody@example.com");

        assertThat(role(jose)).isEqualTo("USER");
        assertThat(audit.lines()).isEmpty();
        assertThat(warnings()).singleElement().asString().contains("no account with this email");

        long later = users.saveAndFlush(new User("nobody@example.com", HASH, "Nobody")).getId();
        run("nobody@example.com");
        assertThat(role(later)).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void doesNothingOnceASuperAdminExistsSoItCantTakeOverLater() {
        long boss = users.saveAndFlush(new User("boss@example.com", HASH, "Boss")).getId();
        jdbc.update("update users set role = 'SUPER_ADMIN' where id = ?", boss);

        run("jose@example.com");

        assertThat(role(jose)).isEqualTo("USER");
        assertThat(role(boss)).isEqualTo("SUPER_ADMIN");
        assertThat(audit.lines()).isEmpty();
    }

    @Test
    void theSameAccountAlreadySuperAdminIsLeftAsIs() {
        run("jose@example.com");
        audit.clear();

        run("jose@example.com");

        assertThat(role(jose)).isEqualTo("SUPER_ADMIN");
        assertThat(audit.lines()).isEmpty();
    }

    @Test
    void disabledAccountIsSkippedWithAWarning() {
        jdbc.update("update users set disabled_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-10-15T08:00:00Z")), jose);
        // A real startup reads the row fresh; this test's persistence context still holds the user.
        entityManager.clear();

        run("jose@example.com");

        assertThat(role(jose)).isEqualTo("USER");
        assertThat(audit.lines()).isEmpty();
        assertThat(warnings()).singleElement().asString().contains("disabled");
        assertThat(users.existsByRole(Role.SUPER_ADMIN)).isFalse();
    }
}
