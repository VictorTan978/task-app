package com.taskapp.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.taskapp.auth.dto.LoginRequest;
import com.taskapp.auth.dto.RegisterRequest;
import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.SessionAuthenticator;

// High per-IP limits: the cached context's limiter sees every request in this class. Rate limiting: AuthRateLimitControllerTest.
@WebMvcTest(controllers = AuthController.class, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000"})
@Import({SecurityConfig.class, SessionAuthenticator.class})
class AuthControllerTest {

    private static final UserResponse BOB = new UserResponse(1L, "bob@example.com", "Bob");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void registerReturns201WithUserAndNormalizedInput() throws Exception {
        when(authService.register(any())).thenReturn(BOB);

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"  Bob  ","email":"  Bob@Example.com ","password":"correct-horse"}"""))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.displayName").value("Bob"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(cookie().exists("XSRF-TOKEN"));

        verify(authService).register(new RegisterRequest("Bob", "bob@example.com", "correct-horse"));
    }

    @Test
    void registerReportsEveryInvalidFieldOnce() throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"   ","email":"not-an-email","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.instance").value("/api/auth/register"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[1].field").value("email"))
                .andExpect(jsonPath("$.errors[1].code").value("INVALID_EMAIL"))
                .andExpect(jsonPath("$.errors[2].field").value("password"))
                .andExpect(jsonPath("$.errors[2].code").value("PASSWORD_LENGTH"))
                .andExpect(jsonPath("$.errors[2].message").value("Password must be 8 to 72 characters"));

        verifyNoInteractions(authService);
    }

    @Test
    void registerRejectsMissingFieldsAndTooLongValues() throws Exception {
        String longName = "x".repeat(51);
        // 268 chars, syntactically valid (local part 64, labels 49)
        String label = "b".repeat(49);
        String longEmail = "a".repeat(64) + "@" + String.join(".", label, label, label, label) + ".com";
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + longName + "\",\"email\":\"" + longEmail + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.errors[1].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.errors[2].field").value("password"))
                .andExpect(jsonPath("$.errors[2].code").value("REQUIRED"));
    }

    @Test
    void registerRejectsPasswordOver72Utf8Bytes() throws Exception {
        String password = "é".repeat(40); // 40 chars, 80 bytes
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Bob\",\"email\":\"bob@example.com\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].code").value("PASSWORD_LENGTH"));
    }

    @Test
    void registerDuplicateEmailReturns409() throws Exception {
        when(authService.register(any())).thenThrow(new RegistrationConflictException(true, false));

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Bob","email":"bob@example.com","password":"correct-horse"}"""))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.detail").value("An account with this email already exists"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].code").value("EMAIL_TAKEN"));
    }

    @Test
    void registerDuplicateDisplayNameReturns409DisplayNameTaken() throws Exception {
        when(authService.register(any())).thenThrow(new RegistrationConflictException(false, true));

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Bob","email":"bob@example.com","password":"correct-horse"}"""))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DISPLAY_NAME_TAKEN"))
                .andExpect(jsonPath("$.detail").value("This display name is already taken"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                .andExpect(jsonPath("$.errors[0].code").value("DISPLAY_NAME_TAKEN"))
                .andExpect(jsonPath("$.errors[0].message").value("This display name is already taken"));
    }

    @Test
    void registerBothTakenReturns409EmailTakenWithTwoFieldErrors() throws Exception {
        when(authService.register(any())).thenThrow(new RegistrationConflictException(true, true));

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Bob","email":"bob@example.com","password":"correct-horse"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.detail").value("An account with this email already exists"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.errors[0].message").value("An account with this email already exists"))
                .andExpect(jsonPath("$.errors[1].field").value("displayName"))
                .andExpect(jsonPath("$.errors[1].code").value("DISPLAY_NAME_TAKEN"))
                .andExpect(jsonPath("$.errors[1].message").value("This display name is already taken"));
    }

    @Test
    void malformedJsonReturns400MalformedRequest() throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void registerWithoutCsrfReturns403() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Bob","email":"bob@example.com","password":"correct-horse"}"""))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(jsonPath("$.title").value("Forbidden"));

        verifyNoInteractions(authService);
    }

    @Test
    void loginReturnsUserAndStartsSession() throws Exception {
        when(authService.login(any())).thenReturn(BOB);

        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":" BOB@example.com","password":"x"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(cookie().exists("XSRF-TOKEN"));

        verify(authService).login(new LoginRequest("bob@example.com", "x"));
    }

    @Test
    void loginWithBadCredentialsReturns401() throws Exception {
        when(authService.login(any())).thenThrow(
                new InvalidCredentialsException(InvalidCredentialsException.Reason.BAD_PASSWORD, "bob@example.com"));

        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"bob@example.com","password":"wrong-password"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void loginValidatesOnlyPresenceAndFormat() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nope","password":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_EMAIL"))
                .andExpect(jsonPath("$.errors[1].code").value("REQUIRED"));
    }

    /**
     * The XSRF-TOKEN cookie on 401s is asserted in AuthIntegrationTest: spring-security-test's csrf()
     * replaces the shared CsrfFilter repository, so cookie issuance is order-dependent under MockMvc.
     */
    @Test
    void meWithoutSessionReturns401Problem() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.instance").value("/api/auth/me"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        when(authService.getUser(1L)).thenReturn(BOB);
        var principal = new AuthenticatedUser(1L, "bob@example.com", "Bob");

        mockMvc.perform(get("/api/auth/me")
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.displayName").value("Bob"));
    }

    @Test
    void logoutReturns204AndExpiresSessionCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("JSESSIONID=; Path=/; Max-Age=0"))))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void logoutWithoutCsrfReturns403() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void otherApiPathsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
