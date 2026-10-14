package com.taskapp.auth;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.validation.DirectFieldBindingResult;

import com.taskapp.auth.dto.ForgotPasswordRequest;
import com.taskapp.auth.dto.ResetPasswordRequest;
import com.taskapp.auth.dto.TokenRequest;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.ratelimit.RateLimitedException;

/**
 * specs/email-auth.md at the HTTP layer: status codes, error shapes, cookies, CSRF, per-IP limits (real limiter,
 * small limits, one client IP per test) and audit lines. The service is mocked; flows run in EmailAuthIntegrationTest.
 */
@WebMvcTest(controllers = EmailAuthController.class, properties = {
        "app.rate-limit.forgot-password-per-ip-per-hour=2",
        "app.rate-limit.verify-email-per-ip-per-hour=2",
        "app.rate-limit.check-reset-token-per-ip-per-hour=2",
        "app.rate-limit.reset-password-per-ip-per-hour=2"})
@Import({SecurityConfig.class, SessionAuthenticator.class, UnchangedSessionUserLookup.class})
class EmailAuthControllerTest {

    private static final AuthenticatedUser PRINCIPAL = new AuthenticatedUser(1L, "bob@example.com", "Bob");
    private static final String TOKEN = "Zr0b1y6p6eS4lJx3n9QhVt2Kc7aWm5dUo8fXg1iHsNk";
    private static final String VERIFY = "/api/auth/verify-email";
    private static final String FORGOT = "/api/auth/forgot-password";
    private static final String CHECK = "/api/auth/check-reset-token";
    private static final String RESET = "/api/auth/reset-password";
    private static final String RESEND = "/api/account/resend-verification";
    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailAuthService emailAuthService;

    private final SecurityLogCapture capture = new SecurityLogCapture();
    /** A fresh client IP per test, so the per-IP counters of the cached context never interfere. */
    private String ip;

    @BeforeEach
    void start() {
        capture.start();
        ip = "10.9.0." + IPS.incrementAndGet();
    }

    @AfterEach
    void stop() {
        capture.stop();
    }

    private RequestPostProcessor fromIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).with(csrf()).with(fromIp()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor signedIn() {
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL, null, List.of()));
    }

    private static String tokenBody(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    // ---- verify-email ----

    @Test
    void verifyEmailReturns204AnonymouslyAndLogsEmailVerified() throws Exception {
        when(emailAuthService.verifyEmail(new TokenRequest(TOKEN))).thenReturn("bob@example.com");

        mockMvc.perform(postJson(VERIFY, tokenBody(TOKEN)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(capture.lines()).containsExactly(
                "SECURITY event=EMAIL_VERIFIED email=bob@example.com ip=" + ip + " path=" + VERIFY);
    }

    @Test
    void invalidTokenIs400TokenInvalidAndOnlyTheLogKnowsWhy() throws Exception {
        when(emailAuthService.verifyEmail(any())).thenThrow(new EmailTokenInvalidException("expired", "bob@example.com"));

        mockMvc.perform(postJson(VERIFY, tokenBody(TOKEN)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("This link is invalid or has expired."))
                .andExpect(jsonPath("$.instance").value(VERIFY))
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(capture.lines()).containsExactly("SECURITY event=EMAIL_TOKEN_REJECTED reason=expired"
                + " email=bob@example.com ip=" + ip + " path=" + VERIFY);
        assertThat(String.join("\n", capture.lines())).doesNotContain(TOKEN);
    }

    @Test
    void publicEndpointsNeedCsrfButNoSession() throws Exception {
        for (String path : List.of(VERIFY, FORGOT, CHECK, RESET)) {
            mockMvc.perform(post(path).with(fromIp()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        }
        verifyNoInteractions(emailAuthService);
    }

    @Test
    void bodyThatIsNotJsonIsMalformedRequest() throws Exception {
        for (String path : List.of(VERIFY, FORGOT, CHECK)) {
            mockMvc.perform(postJson(path, "{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errors").doesNotExist());
        }
        verifyNoInteractions(emailAuthService);
    }

    @Test
    void verifyEmailPerIpLimitIs429WithRetryAfterBeforeTheBodyIsRead() throws Exception {
        mockMvc.perform(postJson(VERIFY, "{not json")).andExpect(status().isBadRequest());
        mockMvc.perform(postJson(VERIFY, tokenBody(TOKEN))).andExpect(status().isNoContent());

        mockMvc.perform(postJson(VERIFY, tokenBody(TOKEN)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.detail").value("Too many attempts. Please try again later."));

        assertThat(capture.lines()).contains("SECURITY event=RATE_LIMITED limit=verify-email-ip email=- ip=" + ip
                + " path=" + VERIFY);
    }

    // ---- forgot-password ----

    @Test
    void forgotPasswordIs202WithNoBodyAndPassesTheNormalizedEmail() throws Exception {
        mockMvc.perform(postJson(FORGOT, "{\"email\":\"  Bob@Example.COM \"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(emailAuthService).requestPasswordReset(new ForgotPasswordRequest("bob@example.com"));
    }

    @Test
    void forgotPasswordValidatesTheEmailLikeRegister() throws Exception {
        mockMvc.perform(postJson(FORGOT, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[0].message").value("Email is required"));
        mockMvc.perform(postJson(FORGOT, "{\"email\":\"not-an-email\"}"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_EMAIL"));
        verifyNoInteractions(emailAuthService);
    }

    @Test
    void forgotPasswordTooLongEmailIsTooLong() throws Exception {
        String label = "b".repeat(49);
        String longEmail = "a".repeat(64) + "@" + String.join(".", label, label, label, label) + ".com";

        mockMvc.perform(postJson(FORGOT, "{\"email\":\"" + longEmail + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.errors[0].message").value("Email must be at most 254 characters"));
    }

    @Test
    void forgotPasswordPerIpLimitCountsInvalidRequestsToo() throws Exception {
        mockMvc.perform(postJson(FORGOT, "{}")).andExpect(status().isBadRequest());
        mockMvc.perform(postJson(FORGOT, "{\"email\":\"a@example.com\"}")).andExpect(status().isAccepted());

        mockMvc.perform(postJson(FORGOT, "{\"email\":\"a@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    // ---- check-reset-token ----

    @Test
    void checkResetTokenIs204OrTokenInvalid() throws Exception {
        mockMvc.perform(postJson(CHECK, tokenBody(TOKEN))).andExpect(status().isNoContent());
        verify(emailAuthService).checkResetToken(new TokenRequest(TOKEN));

        doThrow(new EmailTokenInvalidException("wrong_purpose", null)).when(emailAuthService).checkResetToken(any());
        mockMvc.perform(postJson(CHECK, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        assertThat(capture.lines()).containsExactly("SECURITY event=EMAIL_TOKEN_REJECTED reason=wrong_purpose"
                + " email=- ip=" + ip + " path=" + CHECK);
    }

    // ---- reset-password ----

    @Test
    void resetPasswordSignsThisBrowserOutLikeLogout() throws Exception {
        when(emailAuthService.resetPassword(new ResetPasswordRequest(TOKEN, "battery-staple-42")))
                .thenReturn("bob@example.com");

        MockHttpServletResponse response = mockMvc.perform(postJson(RESET,
                        "{\"token\":\"" + TOKEN + "\",\"newPassword\":\"battery-staple-42\"}").with(signedIn()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("JSESSIONID", 0))
                .andExpect(cookie().maxAge("remember-me", 0))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn().getResponse();

        assertThat(response.getCookie("XSRF-TOKEN").getValue()).isNotEmpty();
        assertThat(capture.lines()).containsExactly(
                "SECURITY event=PASSWORD_RESET_COMPLETED email=bob@example.com ip=" + ip + " path=" + RESET);
    }

    @Test
    void resetPasswordValidationFailureHasTheUsualShapeAndKeepsTheSession() throws Exception {
        DirectFieldBindingResult errors = new DirectFieldBindingResult(new ResetPasswordRequest(TOKEN, "short"),
                "resetPasswordRequest");
        errors.rejectValue("newPassword", "PasswordLength", "Password must be 8 to 72 characters");
        when(emailAuthService.resetPassword(any())).thenThrow(new InvalidFieldsException(errors));

        mockMvc.perform(postJson(RESET, "{\"token\":\"" + TOKEN + "\",\"newPassword\":\"short\"}").with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[0].code").value("PASSWORD_LENGTH"))
                .andExpect(jsonPath("$.errors[0].message").value("Password must be 8 to 72 characters"))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(cookie().doesNotExist("remember-me"));
    }

    @Test
    void resetPasswordInvalidTokenIsTokenInvalidAndNeverSignsOut() throws Exception {
        when(emailAuthService.resetPassword(any())).thenThrow(new EmailTokenInvalidException("used", "bob@example.com"));

        mockMvc.perform(postJson(RESET, "{\"newPassword\":\"x\"}").with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @Test
    void resetPasswordPerIpLimit() throws Exception {
        when(emailAuthService.resetPassword(any())).thenThrow(new EmailTokenInvalidException("unknown", null));
        mockMvc.perform(postJson(RESET, "{}")).andExpect(status().isBadRequest());
        mockMvc.perform(postJson(RESET, "{}")).andExpect(status().isBadRequest());

        mockMvc.perform(postJson(RESET, "{}")).andExpect(status().isTooManyRequests());
    }

    // ---- resend-verification ----

    @Test
    void resendNeedsASignedInUser() throws Exception {
        mockMvc.perform(post(RESEND).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(emailAuthService);
    }

    @Test
    void resendIs204AndIgnoresABody() throws Exception {
        mockMvc.perform(post(RESEND).with(csrf()).with(signedIn())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"anything\":1}"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().doesNotExist("JSESSIONID"));
        verify(emailAuthService).resendVerification(1L);
    }

    @Test
    void resendWhenAlreadyConfirmedIs409AlreadyVerified() throws Exception {
        doThrow(new AlreadyVerifiedException()).when(emailAuthService).resendVerification(1L);

        mockMvc.perform(post(RESEND).with(csrf()).with(signedIn()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"))
                .andExpect(jsonPath("$.detail").value("Your email address is already confirmed."))
                .andExpect(jsonPath("$.instance").value(RESEND));
    }

    @Test
    void resendOverTheLimitIs429WithRetryAfter() throws Exception {
        doThrow(new RateLimitedException(Duration.ofSeconds(90), RateLimitedException.Limit.RESEND_VERIFICATION_USER,
                "bob@example.com")).when(emailAuthService).resendVerification(1L);

        mockMvc.perform(post(RESEND).with(csrf()).with(signedIn()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "90"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(capture.lines()).anyMatch(l -> l.startsWith(
                "SECURITY event=RATE_LIMITED limit=resend-verification-user email=bob@example.com"));
    }
}
