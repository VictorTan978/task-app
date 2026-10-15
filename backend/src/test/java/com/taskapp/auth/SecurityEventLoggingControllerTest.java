package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static com.taskapp.security.TestSessions.signedInSession;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.ratelimit.RateLimitedException;

import ch.qos.logback.classic.Level;

/** AC11: each security event on the auth endpoints writes exactly one SECURITY line with the right fields. */
@WebMvcTest(controllers = AuthController.class, properties = {
        "app.rate-limit.login-per-ip-per-minute=1",
        "app.rate-limit.register-per-ip-per-hour=1"})
@Import({SecurityConfig.class, SessionAuthenticator.class, UnchangedSessionUserLookup.class})
class SecurityEventLoggingControllerTest {

    private static final UserResponse BOB = new UserResponse(1L, "bob@example.com", "Bob", false, true, List.of());
    private static final String LOGIN = "{\"email\":\"Bob@Example.com\",\"password\":\"correct-horse\"}";
    private static final String REGISTER =
            "{\"displayName\":\"Bob\",\"email\":\"bob@example.com\",\"password\":\"correct-horse\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    private final SecurityLogCapture capture = new SecurityLogCapture();

    @BeforeEach
    void start() {
        capture.start();
    }

    @AfterEach
    void stop() {
        capture.stop();
    }

    /** Per-IP limit is 1, so every request uses its own IP unless the test wants the limit. */
    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder postJson(String path, String body, String ip) {
        return post(path).with(csrf()).with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static UsernamePasswordAuthenticationToken signedIn() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(1L, "bob@example.com", "Bob"), null, List.of());
    }

    private void assertSingleLine(Level level, String expected) {
        assertThat(capture.lines()).containsExactly(expected);
        assertThat(capture.events().get(0).getLevel()).isEqualTo(level);
    }

    @Test
    void registerSuccess() throws Exception {
        when(authService.register(any())).thenReturn(BOB);

        mockMvc.perform(postJson("/api/auth/register", REGISTER, "10.1.0.1")).andExpect(status().isCreated());

        assertSingleLine(Level.INFO,
                "SECURITY event=REGISTER_SUCCESS email=bob@example.com ip=10.1.0.1 path=/api/auth/register");
    }

    @Test
    void failedRegistrationIsNotLoggedAsRegistration() throws Exception {
        when(authService.register(any())).thenThrow(new RegistrationConflictException(true, false));

        mockMvc.perform(postJson("/api/auth/register", REGISTER, "10.1.0.2")).andExpect(status().isConflict());

        assertThat(capture.lines()).isEmpty();
    }

    @Test
    void loginSuccess() throws Exception {
        when(authService.login(any())).thenReturn(BOB);

        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.1.0.3")).andExpect(status().isOk());

        assertSingleLine(Level.INFO,
                "SECURITY event=LOGIN_SUCCESS email=bob@example.com ip=10.1.0.3 path=/api/auth/login");
    }

    @Test
    void loginFailureWrongPassword() throws Exception {
        when(authService.login(any())).thenThrow(
                new InvalidCredentialsException(InvalidCredentialsException.Reason.BAD_PASSWORD, "bob@example.com"));

        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.1.0.4")).andExpect(status().isUnauthorized());

        assertSingleLine(Level.WARN, "SECURITY event=LOGIN_FAILURE reason=bad_password email=bob@example.com"
                + " ip=10.1.0.4 path=/api/auth/login");
    }

    @Test
    void loginFailureUnknownEmail() throws Exception {
        when(authService.login(any())).thenThrow(new InvalidCredentialsException(
                InvalidCredentialsException.Reason.UNKNOWN_EMAIL, "nobody@example.com"));

        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.1.0.5")).andExpect(status().isUnauthorized());

        assertSingleLine(Level.WARN, "SECURITY event=LOGIN_FAILURE reason=unknown_email email=nobody@example.com"
                + " ip=10.1.0.5 path=/api/auth/login");
    }

    @Test
    void crLfInLoggedEmailIsNeutralised() throws Exception {
        when(authService.login(any())).thenThrow(new InvalidCredentialsException(
                InvalidCredentialsException.Reason.UNKNOWN_EMAIL, "eve@example.com\r\nSECURITY event=LOGIN_SUCCESS"));

        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.1.0.6")).andExpect(status().isUnauthorized());

        assertSingleLine(Level.WARN, "SECURITY event=LOGIN_FAILURE reason=unknown_email"
                + " email=eve@example.com%0D%0ASECURITY%20event=LOGIN_SUCCESS ip=10.1.0.6 path=/api/auth/login");
    }

    @Test
    void logoutWhenSignedInAndAnonymous() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(csrf()).with(from("10.1.0.7")).with(signedInSession(signedIn())))
                .andExpect(status().isNoContent());
        assertSingleLine(Level.INFO,
                "SECURITY event=LOGOUT email=bob@example.com ip=10.1.0.7 path=/api/auth/logout");

        capture.clear();
        mockMvc.perform(post("/api/auth/logout").with(csrf()).with(from("10.1.0.7")))
                .andExpect(status().isNoContent());
        assertSingleLine(Level.INFO, "SECURITY event=LOGOUT email=- ip=10.1.0.7 path=/api/auth/logout");
    }

    @Test
    void rateLimitLoginPerIp() throws Exception {
        when(authService.login(any())).thenReturn(BOB);
        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.1.0.8")).andExpect(status().isOk());
        capture.clear();

        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.1.0.8")).andExpect(status().isTooManyRequests());

        assertSingleLine(Level.WARN,
                "SECURITY event=RATE_LIMITED limit=login-ip email=- ip=10.1.0.8 path=/api/auth/login");
    }

    @Test
    void rateLimitRegisterPerIp() throws Exception {
        when(authService.register(any())).thenReturn(BOB);
        mockMvc.perform(postJson("/api/auth/register", REGISTER, "10.1.0.9")).andExpect(status().isCreated());
        capture.clear();

        mockMvc.perform(postJson("/api/auth/register", REGISTER, "10.1.0.9")).andExpect(status().isTooManyRequests());

        assertSingleLine(Level.WARN,
                "SECURITY event=RATE_LIMITED limit=register-ip email=- ip=10.1.0.9 path=/api/auth/register");
    }

    @Test
    void rateLimitLoginPerEmail() throws Exception {
        when(authService.login(any())).thenThrow(new RateLimitedException(Duration.ofMinutes(15),
                RateLimitedException.Limit.LOGIN_EMAIL, "bob@example.com"));

        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.1.0.10")).andExpect(status().isTooManyRequests());

        assertSingleLine(Level.WARN, "SECURITY event=RATE_LIMITED limit=login-email email=bob@example.com"
                + " ip=10.1.0.10 path=/api/auth/login");
    }

    @Test
    void unauthenticatedFromSecurityChain() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(from("10.1.0.11"))).andExpect(status().isUnauthorized());

        assertSingleLine(Level.WARN, "SECURITY event=UNAUTHENTICATED email=- ip=10.1.0.11 path=/api/auth/me");
    }

    @Test
    void unauthenticatedBecauseSessionUserNoLongerExists() throws Exception {
        when(authService.getUser(anyLong())).thenThrow(new UnauthenticatedException());

        mockMvc.perform(get("/api/auth/me").with(from("10.1.0.12")).with(signedInSession(signedIn())))
                .andExpect(status().isUnauthorized());

        assertSingleLine(Level.WARN,
                "SECURITY event=UNAUTHENTICATED email=bob@example.com ip=10.1.0.12 path=/api/auth/me");
    }

    @Test
    void csrfRejection() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(from("10.1.0.13")).with(signedInSession(signedIn())))
                .andExpect(status().isForbidden());

        assertSingleLine(Level.WARN,
                "SECURITY event=CSRF_REJECTED email=bob@example.com ip=10.1.0.13 path=/api/auth/logout");
    }

    @Test
    void csrfRejectionAnonymous() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(from("10.1.0.14")).contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN))
                .andExpect(status().isForbidden());

        assertSingleLine(Level.WARN, "SECURITY event=CSRF_REJECTED email=- ip=10.1.0.14 path=/api/auth/login");
    }
}
