package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.taskapp.security.TestSessions.signedInSession;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.taskapp.auth.dto.ChangeDisplayNameRequest;
import com.taskapp.auth.dto.ChangePasswordRequest;
import com.taskapp.auth.dto.DeleteAccountRequest;
import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.SessionAttributes;
import com.taskapp.security.SessionRenewal;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.ratelimit.RateLimitedException;
import com.taskapp.security.Role;

import ch.qos.logback.classic.Level;

/** AC3-AC16 at the HTTP layer: validation, error shapes, status precedence, cookies and audit lines. */
@WebMvcTest(controllers = AccountController.class)
@Import({SecurityConfig.class, SessionAuthenticator.class, UnchangedSessionUserLookup.class})
class AccountControllerTest {

    private static final AuthenticatedUser PRINCIPAL = new AuthenticatedUser(1L, "bob@example.com", "Bob");
    private static final String DISPLAY_NAME = "/api/account/display-name";
    private static final String PASSWORD = "/api/account/password";
    private static final String DELETE = "/api/account/delete";
    private static final String LOGOUT_OTHERS = "/api/account/logout-other-sessions";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    private final SecurityLogCapture capture = new SecurityLogCapture();

    @BeforeEach
    void start() {
        capture.start();
    }

    @AfterEach
    void stop() {
        capture.stop();
    }

    private static RequestPostProcessor signedIn() {
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL, null, List.of()));
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RateLimitedException limited() {
        return new RateLimitedException(Duration.ofSeconds(840), RateLimitedException.Limit.PASSWORD_CHECK_USER,
                "bob@example.com");
    }

    // --- display name ---

    @Test
    void changeDisplayNameReturnsUpdatedUserAndLogsOnce() throws Exception {
        UserResponse bobby = new UserResponse(1L, "bob@example.com", "Bobby", false, true, List.of(), Role.USER);
        when(accountService.changeDisplayName(eq(1L), any())).thenReturn(new DisplayNameChange(bobby, true));

        mockMvc.perform(json(put(DISPLAY_NAME), "{\"displayName\":\"  Bobby \"}")
                        .with(csrf()).with(signedIn()).with(from("10.2.0.1")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.displayName").value("Bobby"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));

        verify(accountService).changeDisplayName(1L, new ChangeDisplayNameRequest("Bobby"));
        assertThat(capture.lines()).containsExactly(
                "SECURITY event=DISPLAY_NAME_CHANGED email=bob@example.com ip=10.2.0.1 path=/api/account/display-name");
        assertThat(capture.events().get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void unchangedDisplayNameIsNotLogged() throws Exception {
        when(accountService.changeDisplayName(eq(1L), any()))
                .thenReturn(new DisplayNameChange(new UserResponse(1L, "bob@example.com", "Bob", false, true, List.of(), Role.USER), false));

        mockMvc.perform(json(put(DISPLAY_NAME), "{\"displayName\":\"Bob\"}").with(csrf()).with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Bob"));

        assertThat(capture.lines()).isEmpty();
    }

    @Test
    void blankOrMissingDisplayNameIsRequired() throws Exception {
        for (String body : List.of("{\"displayName\":\"   \"}", "{\"displayName\":\"\"}", "{\"displayName\":null}", "{}")) {
            mockMvc.perform(json(put(DISPLAY_NAME), body).with(csrf()).with(signedIn()))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.instance").value(DISPLAY_NAME))
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                    .andExpect(jsonPath("$.errors[0].message").value("Display name is required"));
        }
        verifyNoInteractions(accountService);
    }

    @Test
    void displayNameOver50CharactersAfterTrimmingIsTooLong() throws Exception {
        mockMvc.perform(json(put(DISPLAY_NAME), "{\"displayName\":\"" + "x".repeat(51) + "\"}")
                        .with(csrf()).with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.errors[0].message").value("Display name must be at most 50 characters"));

        when(accountService.changeDisplayName(eq(1L), any())).thenReturn(new DisplayNameChange(
                new UserResponse(1L, "bob@example.com", "x".repeat(50), false, true, List.of(), Role.USER), true));
        mockMvc.perform(json(put(DISPLAY_NAME), "{\"displayName\":\"  " + "x".repeat(50) + "  \"}")
                        .with(csrf()).with(signedIn()))
                .andExpect(status().isOk());
    }

    @Test
    void takenDisplayNameIs409() throws Exception {
        when(accountService.changeDisplayName(eq(1L), any())).thenThrow(new DisplayNameTakenException());

        mockMvc.perform(json(put(DISPLAY_NAME), "{\"displayName\":\"jose\"}").with(csrf()).with(signedIn()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.code").value("DISPLAY_NAME_TAKEN"))
                .andExpect(jsonPath("$.detail").value("This display name is already taken"))
                .andExpect(jsonPath("$.instance").value(DISPLAY_NAME))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                .andExpect(jsonPath("$.errors[0].code").value("DISPLAY_NAME_TAKEN"))
                .andExpect(jsonPath("$.errors[0].message").value("This display name is already taken"));
        assertThat(capture.lines()).isEmpty();
    }

    @Test
    void malformedBodyIsMalformedRequest() throws Exception {
        for (String path : List.of(DISPLAY_NAME, PASSWORD)) {
            mockMvc.perform(json(put(path), "{not json").with(csrf()).with(signedIn()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errors").doesNotExist());
        }
        mockMvc.perform(json(post(DELETE), "{not json").with(csrf()).with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(accountService);
    }

    // --- password ---

    @Test
    void changePasswordReturns204WithoutBodyOrCookiesAndLogs() throws Exception {
        when(accountService.changePassword(eq(1L), any(), any())).thenReturn(new UserResponse(1L, "bob@example.com", "Bob", false, true, List.of(), Role.USER));

        mockMvc.perform(json(put(PASSWORD), "{\"currentPassword\":\"correct-horse\",\"newPassword\":\"battery-staple-42\"}")
                        .with(csrf()).with(signedIn()).with(from("10.2.0.2")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(cookie().doesNotExist("JSESSIONID"));

        verify(accountService).changePassword(eq(1L),
                eq(new ChangePasswordRequest("correct-horse", "battery-staple-42")), any());
        assertThat(capture.lines()).containsExactly(
                "SECURITY event=PASSWORD_CHANGED email=bob@example.com ip=10.2.0.2 path=/api/account/password",
                "SECURITY event=OTHER_SESSIONS_LOGGED_OUT trigger=password_change email=bob@example.com ip=10.2.0.2"
                        + " path=/api/account/password");
        assertThat(capture.events().get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(capture.events().get(1).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void changePasswordValidation() throws Exception {
        mockMvc.perform(json(put(PASSWORD), "{\"currentPassword\":\" \",\"newPassword\":\"\"}")
                        .with(csrf()).with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[0].message").value("Current password is required"))
                .andExpect(jsonPath("$.errors[1].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[1].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[1].message").value("New password is required"));

        for (String newPassword : List.of("1234567", "x".repeat(73), "é".repeat(40))) {
            mockMvc.perform(json(put(PASSWORD), "{\"currentPassword\":\"x\",\"newPassword\":\"" + newPassword + "\"}")
                            .with(csrf()).with(signedIn()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                    .andExpect(jsonPath("$.errors[0].code").value("PASSWORD_LENGTH"))
                    .andExpect(jsonPath("$.errors[0].message").value("Password must be 8 to 72 characters"));
        }
        verifyNoInteractions(accountService);
        assertThat(capture.lines()).isEmpty();
    }

    @Test
    void wrongCurrentPasswordIs400WrongPasswordAndLogsFailure() throws Exception {
        when(accountService.changePassword(eq(1L), any(), any())).thenThrow(
                new WrongPasswordException(WrongPasswordException.Action.CHANGE_PASSWORD, "bob@example.com"));

        mockMvc.perform(json(put(PASSWORD), "{\"currentPassword\":\"wrong-horse-123\",\"newPassword\":\"battery-staple-42\"}")
                        .with(csrf()).with(signedIn()).with(from("10.2.0.3")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"))
                .andExpect(jsonPath("$.detail").value("Current password is incorrect"))
                .andExpect(jsonPath("$.instance").value(PASSWORD))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"))
                .andExpect(jsonPath("$.errors[0].code").value("WRONG_PASSWORD"))
                .andExpect(jsonPath("$.errors[0].message").value("Current password is incorrect"));

        assertThat(capture.lines()).containsExactly("SECURITY event=PASSWORD_CHECK_FAILURE action=change_password"
                + " email=bob@example.com ip=10.2.0.3 path=/api/account/password");
        assertThat(capture.events().get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void passwordCheckLimitIs429WithRetryAfterAndLogs() throws Exception {
        when(accountService.changePassword(eq(1L), any(), any())).thenThrow(limited());
        when(accountService.deleteAccount(eq(1L), any())).thenThrow(limited());

        mockMvc.perform(json(put(PASSWORD), "{\"currentPassword\":\"correct-horse\",\"newPassword\":\"battery-staple-42\"}")
                        .with(csrf()).with(signedIn()).with(from("10.2.0.4")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "840"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.detail").value("Too many attempts. Please try again later."))
                .andExpect(jsonPath("$.instance").value(PASSWORD))
                .andExpect(jsonPath("$.errors").doesNotExist());
        mockMvc.perform(json(post(DELETE), "{\"password\":\"correct-horse\"}")
                        .with(csrf()).with(signedIn()).with(from("10.2.0.4")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "840"))
                .andExpect(jsonPath("$.instance").value(DELETE))
                .andExpect(cookie().doesNotExist("JSESSIONID"));

        assertThat(capture.lines()).containsExactly(
                "SECURITY event=RATE_LIMITED limit=password-check-user email=bob@example.com ip=10.2.0.4"
                        + " path=/api/account/password",
                "SECURITY event=RATE_LIMITED limit=password-check-user email=bob@example.com ip=10.2.0.4"
                        + " path=/api/account/delete");
    }

    // --- delete ---

    @Test
    void deleteReturns204SignsOutAndLogs() throws Exception {
        when(accountService.deleteAccount(eq(1L), any())).thenReturn(new UserResponse(1L, "bob@example.com", "Bob", false, true, List.of(), Role.USER));

        mockMvc.perform(json(post(DELETE), "{\"password\":\"correct-horse\"}")
                        .with(csrf()).with(signedIn()).with(from("10.2.0.5")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("JSESSIONID=; Path=/; Max-Age=0"))))
                .andExpect(cookie().exists("XSRF-TOKEN"));

        verify(accountService).deleteAccount(1L, new DeleteAccountRequest("correct-horse", null));
        assertThat(capture.lines()).containsExactly(
                "SECURITY event=ACCOUNT_DELETED email=bob@example.com ip=10.2.0.5 path=/api/account/delete");
        assertThat(capture.events().get(0).getLevel()).isEqualTo(Level.INFO);
    }

    /**
     * 0.8.0: which field is required depends on the account, so the service validates (AccountServiceTest); its
     * InvalidFieldsException renders exactly like a @Valid failure.
     */
    @Test
    void deleteValidationErrorsFromTheServiceKeepTheValidationShape() throws Exception {
        for (String field : List.of("password", "email")) {
            org.springframework.validation.DirectFieldBindingResult errors =
                    new org.springframework.validation.DirectFieldBindingResult(
                            new DeleteAccountRequest(null, null), "deleteAccountRequest");
            String message = field.equals("password") ? "Password is required" : "Email is required";
            errors.rejectValue(field, "NotBlank", message);
            when(accountService.deleteAccount(eq(1L), any())).thenThrow(new InvalidFieldsException(errors));

            mockMvc.perform(json(post(DELETE), "{}").with(csrf()).with(signedIn()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value(field))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                    .andExpect(jsonPath("$.errors[0].message").value(message))
                    .andExpect(cookie().doesNotExist("JSESSIONID"));
        }
    }

    @Test
    void deletePassesBothFieldsToTheServiceWithTheEmailTrimmed() throws Exception {
        when(accountService.deleteAccount(eq(1L), any())).thenReturn(new UserResponse(1L, "bob@gmail.com", "Bob",
                true, false, List.of(), Role.USER));

        mockMvc.perform(json(post(DELETE), "{\"email\":\"  Bob@Gmail.com \",\"password\":\"x\"}")
                        .with(csrf()).with(signedIn()))
                .andExpect(status().isNoContent());

        verify(accountService).deleteAccount(1L, new DeleteAccountRequest("x", "Bob@Gmail.com"));
    }

    @Test
    void wrongDeleteEmailIs400EmailMismatchKeepsSessionAndLogsFailure() throws Exception {
        when(accountService.deleteAccount(eq(1L), any())).thenThrow(new EmailMismatchException("bob@gmail.com"));

        mockMvc.perform(json(post(DELETE), "{\"email\":\"someone@else.com\"}")
                        .with(csrf()).with(signedIn()).with(from("10.2.0.9")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_MISMATCH"))
                .andExpect(jsonPath("$.detail").value("Email doesn't match"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].code").value("EMAIL_MISMATCH"))
                .andExpect(jsonPath("$.errors[0].message").value("Email doesn't match"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));

        assertThat(capture.lines()).containsExactly("SECURITY event=PASSWORD_CHECK_FAILURE action=delete_account_email"
                + " email=bob@gmail.com ip=10.2.0.9 path=/api/account/delete");
    }

    @Test
    void changePasswordWithoutPasswordIs409PasswordRequired() throws Exception {
        when(accountService.changePassword(eq(1L), any(), any())).thenThrow(
                new PasswordRequiredException(PasswordRequiredException.Action.CHANGE_PASSWORD));

        mockMvc.perform(json(put(PASSWORD), "{\"currentPassword\":\"anything\",\"newPassword\":\"battery-staple-42\"}")
                        .with(csrf()).with(signedIn()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PASSWORD_REQUIRED"))
                .andExpect(jsonPath("$.detail")
                        .value("Your account has no password. Set one with 'Forgot password?' on the sign-in page."))
                .andExpect(jsonPath("$.instance").value(PASSWORD))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void changePasswordValidationRunsBeforePasswordRequired() throws Exception {
        mockMvc.perform(json(put(PASSWORD), "{}").with(csrf()).with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(accountService);
    }

    @Test
    void wrongDeletePasswordIs400KeepsSessionAndLogsFailure() throws Exception {
        when(accountService.deleteAccount(eq(1L), any())).thenThrow(
                new WrongPasswordException(WrongPasswordException.Action.DELETE_ACCOUNT, "bob@example.com"));

        mockMvc.perform(json(post(DELETE), "{\"password\":\"wrong-horse-123\"}")
                        .with(csrf()).with(signedIn()).with(from("10.2.0.6")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"))
                .andExpect(jsonPath("$.detail").value("Password is incorrect"))
                .andExpect(jsonPath("$.instance").value(DELETE))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].code").value("WRONG_PASSWORD"))
                .andExpect(jsonPath("$.errors[0].message").value("Password is incorrect"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(capture.lines()).containsExactly("SECURITY event=PASSWORD_CHECK_FAILURE action=delete_account"
                + " email=bob@example.com ip=10.2.0.6 path=/api/account/delete");
    }

    // --- log out other sessions (specs/sessions.md AC10, AC14, AC15) ---

    private static MockHttpSession sessionValidFrom(Instant validFrom) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAttributes.EXPIRES_AT, validFrom.plus(Duration.ofHours(12)));
        session.setAttribute(SessionAttributes.VALID_FROM, validFrom);
        return session;
    }

    @Test
    void logoutOtherSessionsReturns204RenewsTheSessionAndLogs() throws Exception {
        when(accountService.logoutOtherSessions(eq(1L), any())).thenReturn(new UserResponse(1L, "bob@example.com", "Bob", false, true, List.of(), Role.USER));
        Instant signedInAt = Instant.now().minus(Duration.ofHours(1));
        MockHttpSession session = sessionValidFrom(signedInAt);
        session.setAttribute(SessionAttributes.REMEMBER_ME_SERIES, "A".repeat(42) + "Q");
        String idBefore = session.getId();

        mockMvc.perform(post(LOGOUT_OTHERS).session(session).with(csrf()).with(signedIn()).with(from("10.2.0.7")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist("Set-Cookie"));

        ArgumentCaptor<SessionRenewal> renewal = ArgumentCaptor.forClass(SessionRenewal.class);
        verify(accountService).logoutOtherSessions(eq(1L), renewal.capture());
        assertThat(renewal.getValue().previousValidFrom()).isEqualTo(signedInAt);
        assertThat(renewal.getValue().now()).isAfter(signedInAt);
        assertThat(renewal.getValue().keepSeries()).isEqualTo("A".repeat(42) + "Q");
        assertThat(session.getId()).isNotEqualTo(idBefore);
        assertThat(session.getAttribute(SessionAttributes.VALID_FROM)).isEqualTo(renewal.getValue().now());
        assertThat(session.getAttribute(SessionAttributes.EXPIRES_AT)).isEqualTo(signedInAt.plus(Duration.ofHours(12)));
        assertThat(capture.lines()).containsExactly("SECURITY event=OTHER_SESSIONS_LOGGED_OUT trigger=button"
                + " email=bob@example.com ip=10.2.0.7 path=/api/account/logout-other-sessions");
        assertThat(capture.events().get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void logoutOtherSessionsIgnoresABody() throws Exception {
        when(accountService.logoutOtherSessions(eq(1L), any())).thenReturn(new UserResponse(1L, "bob@example.com", "Bob", false, true, List.of(), Role.USER));

        mockMvc.perform(json(post(LOGOUT_OTHERS), "{not json").with(csrf()).with(signedIn()))
                .andExpect(status().isNoContent());
    }

    @Test
    void failedRenewalRestoresTheSessionsValidityAndKeepsItsId() throws Exception {
        when(accountService.logoutOtherSessions(eq(1L), any())).thenThrow(new UnauthenticatedException());
        Instant signedInAt = Instant.now().minus(Duration.ofHours(1));
        MockHttpSession session = sessionValidFrom(signedInAt);
        String idBefore = session.getId();

        mockMvc.perform(post(LOGOUT_OTHERS).session(session).with(csrf()).with(signedIn()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(session.getAttribute(SessionAttributes.VALID_FROM)).isEqualTo(signedInAt);
        assertThat(session.getId()).isEqualTo(idBefore);
    }

    @Test
    void wrongPasswordOnChangeRestoresTheSessionsValidity() throws Exception {
        when(accountService.changePassword(eq(1L), any(), any())).thenThrow(
                new WrongPasswordException(WrongPasswordException.Action.CHANGE_PASSWORD, "bob@example.com"));
        Instant signedInAt = Instant.now().minus(Duration.ofHours(1));
        MockHttpSession session = sessionValidFrom(signedInAt);
        String idBefore = session.getId();

        mockMvc.perform(json(put(PASSWORD), "{\"currentPassword\":\"wrong-horse\",\"newPassword\":\"battery-staple-42\"}")
                        .session(session).with(csrf()).with(signedIn()))
                .andExpect(status().isBadRequest());

        assertThat(session.getAttribute(SessionAttributes.VALID_FROM)).isEqualTo(signedInAt);
        assertThat(session.getId()).isEqualTo(idBefore);
    }

    @Test
    void logoutOtherSessionsIs401WhenSignedOutAnd403WithoutCsrf() throws Exception {
        mockMvc.perform(post(LOGOUT_OTHERS).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post(LOGOUT_OTHERS).with(signedIn()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(post(LOGOUT_OTHERS))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(accountService);
    }

    // --- AC16: 401 / 403 and their precedence ---

    @Test
    void everyEndpointIs401WhenNotSignedIn() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                json(put(DISPLAY_NAME), "{\"displayName\":\"Bobby\"}"),
                json(put(PASSWORD), "{\"currentPassword\":\"correct-horse\",\"newPassword\":\"battery-staple-42\"}"),
                json(post(DELETE), "{\"password\":\"correct-horse\"}"),
                // 401 comes before validation.
                json(put(DISPLAY_NAME), "{\"displayName\":\"\"}"))) {
            mockMvc.perform(request.with(csrf()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(accountService);
    }

    @Test
    void everyEndpointIs403WithoutCsrfTokenEvenWhenNotSignedIn() throws Exception {
        for (boolean signedIn : List.of(true, false)) {
            for (MockHttpServletRequestBuilder request : List.of(
                    json(put(DISPLAY_NAME), "{\"displayName\":\"Bobby\"}"),
                    json(put(PASSWORD), "{\"currentPassword\":\"correct-horse\",\"newPassword\":\"battery-staple-42\"}"),
                    json(post(DELETE), "{\"password\":\"correct-horse\"}"))) {
                if (signedIn) {
                    request.with(signedIn());
                }
                mockMvc.perform(request)
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
            }
        }
        verifyNoInteractions(accountService);
    }

    @Test
    void passwordsNeverAppearInLogsOrResponses() throws Exception {
        when(accountService.changePassword(anyLong(), any(), any())).thenThrow(
                new WrongPasswordException(WrongPasswordException.Action.CHANGE_PASSWORD, "bob@example.com"));
        String body = mockMvc.perform(json(put(PASSWORD),
                        "{\"currentPassword\":\"Secret-Current-1\",\"newPassword\":\"Secret-New-22\"}")
                        .with(csrf()).with(signedIn()))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Secret-Current-1", "Secret-New-22");
        assertThat(String.join("\n", capture.lines())).doesNotContain("Secret-Current-1", "Secret-New-22");
        assertThat(new ChangePasswordRequest("Secret-Current-1", "Secret-New-22").toString())
                .doesNotContain("Secret-Current-1", "Secret-New-22");
        assertThat(new DeleteAccountRequest("Secret-Current-1", null).toString()).doesNotContain("Secret-Current-1");
    }
}
