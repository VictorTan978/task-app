package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.taskapp.auth.dto.LoginRequest;
import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.RememberMeToken;
import com.taskapp.security.RememberMeTokenStore;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.SessionAttributes;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.SessionUser;
import com.taskapp.security.SessionUserLookup;
import com.taskapp.security.UnchangedSessionUserLookup;

import jakarta.servlet.http.Cookie;

/** Login {@code rememberMe} (contract 0.6.0) at the HTTP layer: cookies, session attributes, validation. */
@WebMvcTest(controllers = AuthController.class, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000"})
@Import({SecurityConfig.class, SessionAuthenticator.class, UnchangedSessionUserLookup.class})
class RememberMeLoginControllerTest {

    private static final UserResponse BOB = new UserResponse(1L, "bob@example.com", "Bob");
    private static final String LOGIN = "/api/auth/login";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RememberMeTokenStore tokenStore;

    /** Replaces UnchangedSessionUserLookup: remember-me sign-ins look the user up by id. */
    @MockitoBean
    private SessionUserLookup sessionUserLookup;

    private MvcResult login(String body, Cookie... cookies) throws Exception {
        var request = post(LOGIN).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
        if (cookies.length > 0) {
            request.cookie(cookies);
        }
        return mockMvc.perform(request).andReturn();
    }

    @Test
    void rememberMeTrueCreatesALoginSetsTheCookieAndARememberMeSession() throws Exception {
        when(authService.login(any())).thenReturn(BOB);
        Instant before = Instant.now();

        MvcResult result = login("""
                {"email":"bob@example.com","password":"correct-horse","rememberMe":true}""");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        verify(authService).login(new LoginRequest("bob@example.com", "correct-horse", true));
        List<String> rememberMe = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(c -> c.startsWith("remember-me=")).toList();
        assertThat(rememberMe).singleElement().asString()
                .matches("remember-me=[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}; .*")
                .contains("; Path=/api", "; Max-Age=2592000", "; HttpOnly", "; SameSite=Lax")
                .doesNotContain("Secure");
        ArgumentCaptor<RememberMeToken> created = ArgumentCaptor.forClass(RememberMeToken.class);
        verify(tokenStore).create(created.capture(), any());
        assertThat(created.getValue().userId()).isEqualTo(1L);
        assertThat(created.getValue().expiresAt()).isBetween(before.plus(Duration.ofDays(30)).minusSeconds(1),
                Instant.now().plus(Duration.ofDays(30)));

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getAttribute(SessionAttributes.EXPIRES_AT)).isEqualTo(created.getValue().expiresAt());
        assertThat(session.getAttribute(SessionAttributes.VALID_FROM)).isNotNull();
        assertThat((String) session.getAttribute(SessionAttributes.REMEMBER_ME_SERIES)).hasSize(43);
    }

    @Test
    void rememberMeFalseOrAbsentIsANormal12HourSessionWithoutCookie() throws Exception {
        when(authService.login(any())).thenReturn(BOB);

        for (String body : List.of(
                "{\"email\":\"bob@example.com\",\"password\":\"correct-horse\"}",
                "{\"email\":\"bob@example.com\",\"password\":\"correct-horse\",\"rememberMe\":false}",
                "{\"email\":\"bob@example.com\",\"password\":\"correct-horse\",\"rememberMe\":null}")) {
            Instant before = Instant.now();
            MvcResult result = login(body);

            assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
            assertThat(result.getResponse().getHeaders("Set-Cookie")).as(body)
                    .noneMatch(c -> c.startsWith("remember-me="));
            MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
            assertThat((Instant) session.getAttribute(SessionAttributes.EXPIRES_AT))
                    .isBetween(before.plus(Duration.ofHours(12)).minusSeconds(1),
                            Instant.now().plus(Duration.ofHours(12)));
            assertThat(session.getAttribute(SessionAttributes.REMEMBER_ME_SERIES)).isNull();
        }
        verify(tokenStore, never()).create(any(), any());
    }

    @Test
    void rememberMeFalseExpiresACookieTheRequestCarried() throws Exception {
        when(authService.login(any())).thenReturn(BOB);

        MvcResult result = login("{\"email\":\"bob@example.com\",\"password\":\"correct-horse\"}",
                new Cookie("remember-me", "anything"));

        assertThat(result.getResponse().getHeaders("Set-Cookie")).filteredOn(c -> c.startsWith("remember-me="))
                .singleElement().asString().contains("Max-Age=0", "Path=/api");
    }

    @Test
    void nonBooleanRememberMeIsMalformedRequest() throws Exception {
        mockMvc.perform(post(LOGIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@example.com\",\"password\":\"x\",\"rememberMe\":\"maybe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verifyNoInteractions(authService, tokenStore);
    }

    @Test
    void failedLoginLeavesTheCookieAlone() throws Exception {
        when(authService.login(any())).thenThrow(new InvalidCredentialsException(
                InvalidCredentialsException.Reason.BAD_PASSWORD, "bob@example.com"));

        mockMvc.perform(post(LOGIN).with(csrf()).cookie(new Cookie("remember-me", "anything"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@example.com\",\"password\":\"x\",\"rememberMe\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(tokenStore);
    }

    @Test
    void logoutAlwaysExpiresTheRememberMeCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues("Set-Cookie",
                        hasItem(containsString("remember-me=; Path=/api; Max-Age=0"))));
    }

    @Test
    void rememberMeCookieIsNotUsedToSignInOnLogout() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(csrf()).cookie(validCookie()))
                .andExpect(status().isNoContent());

        // Only logout's own lookup to end this device's login: no rotation, no sign-in.
        verify(tokenStore).find(any());
        verify(tokenStore, never()).rotate(any(), any(), any(), any());
        verifyNoInteractions(sessionUserLookup);
    }

    @Test
    void validCookieSignsInWithinTheRequestWithARotatedCookie() throws Exception {
        Instant expiresAt = Instant.now().plus(Duration.ofDays(3));
        when(tokenStore.find(any())).thenReturn(Optional.of(
                new RememberMeToken(sha256(SERIES), 1L, sha256(SECRET), null, null, expiresAt)));
        when(tokenStore.rotate(any(), any(), any(), any())).thenReturn(true);
        when(sessionUserLookup.findById(1L)).thenReturn(Optional.of(
                new SessionUser(new AuthenticatedUser(1L, "bob@example.com", "Bob"), null)));
        when(authService.getUser(1L)).thenReturn(BOB);

        MvcResult result = mockMvc.perform(get("/api/auth/me").cookie(validCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andReturn();

        List<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
        // "Exactly one XSRF-TOKEN" is asserted over real HTTP (SessionsIntegrationTest): spring-security-test's
        // csrf() swaps the CSRF repository of this cached context to a session-based one.
        assertThat(setCookies).filteredOn(c -> c.startsWith("remember-me=")).singleElement().asString()
                .startsWith("remember-me=" + SERIES + ".").doesNotContain(SECRET, "Max-Age=0");
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getAttribute(SessionAttributes.EXPIRES_AT)).isEqualTo(expiresAt);
        assertThat(session.getAttribute(SessionAttributes.REMEMBER_ME_SERIES)).isNotNull();
    }

    private static final String SERIES = "A".repeat(42) + "Q";
    private static final String SECRET = "B".repeat(42) + "A";

    private static Cookie validCookie() {
        return new Cookie("remember-me", SERIES + "." + SECRET);
    }

    private static byte[] sha256(String base64Url) throws java.security.NoSuchAlgorithmException {
        return java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.util.Base64.getUrlDecoder().decode(base64Url));
    }
}
