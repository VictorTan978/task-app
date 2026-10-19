package com.taskapp.security.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.taskapp.security.AuthenticatedUser;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

class SecurityEventLoggerTest {

    private final SecurityLogCapture capture = new SecurityLogCapture();

    @BeforeEach
    void start() {
        capture.start();
    }

    @AfterEach
    void stop() {
        capture.stop();
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String ip, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void writesOneLineWithFixedPrefixAndFieldOrder() {
        SecurityEventLogger.log(SecurityEvent.LOGIN_FAILURE, "reason", "bad_password",
                request("203.0.113.7", "/api/auth/login"), "alice@example.com");

        List<ILoggingEvent> events = capture.events();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLoggerName()).isEqualTo("security.audit");
        assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(0).getFormattedMessage()).isEqualTo(
                "SECURITY event=LOGIN_FAILURE reason=bad_password email=alice@example.com ip=203.0.113.7"
                        + " path=/api/auth/login");
    }

    @Test
    void successEventsAreInfoAndMissingValuesAreDash() {
        SecurityEventLogger.log(SecurityEvent.LOGOUT, request("10.0.0.1", "/api/auth/logout"), null);

        assertThat(capture.events().get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(capture.lines()).containsExactly(
                "SECURITY event=LOGOUT email=- ip=10.0.0.1 path=/api/auth/logout");
    }

    @Test
    void levelsAreInfoForSuccessesAndWarnForFailuresAndRejections() {
        for (SecurityEvent event : SecurityEvent.values()) {
            boolean success = event == SecurityEvent.LOGIN_SUCCESS || event == SecurityEvent.REGISTER_SUCCESS
                    || event == SecurityEvent.LOGOUT || event == SecurityEvent.PASSWORD_CHANGED
                    || event == SecurityEvent.DISPLAY_NAME_CHANGED || event == SecurityEvent.ACCOUNT_DELETED
                    // Session events: normal expiries (REMEMBER_ME_REJECTED reason=reused is logged at WARN).
                    || event == SecurityEvent.REMEMBER_ME_LOGIN || event == SecurityEvent.REMEMBER_ME_REJECTED
                    || event == SecurityEvent.SESSION_MAX_LENGTH_REACHED
                    || event == SecurityEvent.OTHER_SESSIONS_LOGGED_OUT
                    // specs/email-auth.md (PASSWORD_RESET_REQUESTED is logged whatever the outcome).
                    || event == SecurityEvent.EMAIL_VERIFICATION_SENT || event == SecurityEvent.EMAIL_VERIFIED
                    || event == SecurityEvent.PASSWORD_RESET_REQUESTED
                    || event == SecurityEvent.PASSWORD_RESET_COMPLETED
                    // specs/google-sign-in.md
                    || event == SecurityEvent.GOOGLE_LOGIN_SUCCESS || event == SecurityEvent.GOOGLE_ACCOUNT_CREATED
                    || event == SecurityEvent.GOOGLE_LINKED || event == SecurityEvent.GOOGLE_UNLINKED
                    // specs/roles-admin.md: changes that happened (refusals and ended sessions are WARN).
                    || event == SecurityEvent.ADMIN_BOOTSTRAPPED || event == SecurityEvent.ADMIN_USER_DISABLED
                    || event == SecurityEvent.ADMIN_USER_ENABLED || event == SecurityEvent.ADMIN_USER_SESSIONS_REVOKED
                    || event == SecurityEvent.ADMIN_ROLE_CHANGED || event == SecurityEvent.ADMIN_USER_DELETED
                    // specs/sharing-team-lists.md (logged whatever the outcome, like PASSWORD_RESET_REQUESTED).
                    || event == SecurityEvent.LIST_INVITATION_EMAIL;
            assertThat(event.level().name()).as(event.name()).isEqualTo(success ? "INFO" : "WARN");
        }
    }

    @Test
    void crLfAndOtherControlCharactersCannotForgeLines() {
        String forgedEmail = "x@example.com\r\nSECURITY event=LOGIN_SUCCESS email=admin@example.com";
        String forgedPath = "/api/tasks\nSECURITY event=LOGOUT \u0085\t\u001b[31m‮";

        SecurityEventLogger.log(SecurityEvent.LOGIN_FAILURE, "reason", "bad_password",
                request("10.0.0.1", forgedPath), forgedEmail);

        String line = capture.lines().get(0);
        assertThat(line).doesNotContain("\r", "\n", "\t", " ", "\u0085", "\u001b", "‮");
        assertThat(line).isEqualTo("SECURITY event=LOGIN_FAILURE reason=bad_password"
                + " email=x@example.com%0D%0ASECURITY%20event=LOGIN_SUCCESS%20email=admin@example.com"
                + " ip=10.0.0.1"
                + " path=/api/tasks%0ASECURITY%20event=LOGOUT%E2%80%A8%C2%85%09%1B[31m%E2%80%AE");
        // Exactly one "event=" token at the start of a field: the forged one is inside the email value.
        assertThat(line.split(" ")).filteredOn(token -> token.startsWith("event=")).hasSize(1);
    }

    @Test
    void percentIsEscapedSoValuesStayUnambiguous() {
        assertThat(SecurityEventLogger.sanitize("a%0Ab")).isEqualTo("a%250Ab");
    }

    @Test
    void longValuesAreTruncated() {
        String sanitized = SecurityEventLogger.sanitize("a".repeat(1000));
        assertThat(sanitized).hasSize(SecurityEventLogger.MAX_VALUE_LENGTH + 3).endsWith("...");
    }

    @Test
    void pathDropsPathParametersAndQueryString() {
        MockHttpServletRequest request = request("10.0.0.1", "/api/tasks;jsessionid=ABC123/7;x=1");
        request.setQueryString("token=secret");

        SecurityEventLogger.log(SecurityEvent.UNAUTHENTICATED, request, null);

        assertThat(capture.lines()).containsExactly("SECURITY event=UNAUTHENTICATED email=- ip=10.0.0.1 path=/api/tasks/7");
    }

    @Test
    void currentUserEmailComesFromTheSignedInPrincipal() {
        assertThat(SecurityEventLogger.currentUserEmail()).isNull();

        AuthenticatedUser bob = new AuthenticatedUser(1L, "bob@example.com", "Bob");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(bob, null, List.of()));

        assertThat(SecurityEventLogger.currentUserEmail()).isEqualTo("bob@example.com");
    }
}
