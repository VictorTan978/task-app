package com.taskapp.auth;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.taskapp.auth.dto.ConnectionResponse;
import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.Role;

/**
 * Contract 0.8.0 with Google sign-in configured: {@code GET /api/auth/providers},
 * {@code DELETE /api/account/connections/google}, and the guard of the flow endpoints that answers before Google is
 * ever contacted (the issuer here is unreachable on purpose; the real flow is in GoogleSignInIntegrationTest).
 */
@WebMvcTest(controllers = ConnectionsController.class, properties = {
        "app.google.client-id=test-client",
        "app.google.client-secret=test-secret",
        "app.google.issuer-uri=http://localhost:1/never-contacted"})
@Import({SecurityConfig.class, SessionAuthenticator.class, UnchangedSessionUserLookup.class})
class ConnectionsControllerTest {

    private static final AuthenticatedUser PRINCIPAL = new AuthenticatedUser(1L, "bob@example.com", "Bob");
    private static final String DISCONNECT = "/api/account/connections/google";

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

    @Test
    void providersSaysGoogleIsOnWithoutSignInAndIgnoresAStaleSession() throws Exception {
        mockMvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"google\":true}", true));
    }

    @Test
    void disconnectReturnsTheUpdatedUserAndLogs() throws Exception {
        when(accountService.disconnectGoogle(1L))
                .thenReturn(new UserResponse(1L, "bob@example.com", "Bob", true, true, List.of(), Role.USER));

        mockMvc.perform(delete(DISCONNECT).with(csrf()).with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":1,"email":"bob@example.com","displayName":"Bob","emailVerified":true,
                         "hasPassword":true,"connections":[],"role":"USER"}""", true))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(capture.lines()).containsExactly(
                "SECURITY event=GOOGLE_UNLINKED email=bob@example.com ip=127.0.0.1 path=" + DISCONNECT);
    }

    @Test
    void userJsonListsConnections() throws Exception {
        when(accountService.disconnectGoogle(1L)).thenReturn(new UserResponse(1L, "bob@example.com", "Bob", true,
                true, List.of(new ConnectionResponse("google", "bob@gmail.com")), Role.USER));

        mockMvc.perform(delete(DISCONNECT).with(csrf()).with(signedIn()))
                .andExpect(jsonPath("$.connections[0].provider").value("google"))
                .andExpect(jsonPath("$.connections[0].email").value("bob@gmail.com"));
    }

    @Test
    void disconnectWithoutConnectionIs404() throws Exception {
        when(accountService.disconnectGoogle(1L))
                .thenThrow(new ConnectionNotFoundException("No Google account is connected"));

        mockMvc.perform(delete(DISCONNECT).with(csrf()).with(signedIn()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("No Google account is connected"))
                .andExpect(jsonPath("$.instance").value(DISCONNECT));
    }

    @Test
    void disconnectWithoutPasswordIs409() throws Exception {
        when(accountService.disconnectGoogle(1L))
                .thenThrow(new PasswordRequiredException(PasswordRequiredException.Action.DISCONNECT_GOOGLE));

        mockMvc.perform(delete(DISCONNECT).with(csrf()).with(signedIn()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PASSWORD_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("Set a password first so you don't lose access to your account."));
        assertThat(capture.lines()).isEmpty();
    }

    @Test
    void disconnectIs403WithoutCsrfThen401WhenSignedOut() throws Exception {
        mockMvc.perform(delete(DISCONNECT).with(signedIn()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(delete(DISCONNECT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(delete(DISCONNECT).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(accountService);
    }

    // --- flow endpoints: answered by GoogleFlowGuardFilter before Google is contacted

    @Test
    void startWithoutCsrfIs403() throws Exception {
        mockMvc.perform(post("/api/auth/oauth2/authorization/google").param("intent", "login"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void startWithGetIs405() throws Exception {
        mockMvc.perform(get("/api/auth/oauth2/authorization/google"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unknownIntentFailsWithoutVisitingGoogle() throws Exception {
        mockMvc.perform(post("/api/auth/oauth2/authorization/google").param("intent", "admin").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.taskapp.test/login?oauth=google_failed"));
        assertThat(capture.lines()).anySatisfy(line -> assertThat(line)
                .startsWith("SECURITY event=GOOGLE_LOGIN_FAILURE intent=- reason=invalid_intent"));
    }

    @Test
    void connectWithoutSessionFailsToLoginWithoutVisitingGoogle() throws Exception {
        mockMvc.perform(post("/api/auth/oauth2/authorization/google").param("intent", "connect").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.taskapp.test/login?oauth=google_failed"));
    }

    @Test
    void unreachableGoogleFailsToTheIntentsFailureRoute() throws Exception {
        mockMvc.perform(post("/api/auth/oauth2/authorization/google").param("intent", "connect").with(csrf())
                        .with(signedIn()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://app.taskapp.test/account?oauth=google_connect_failed"));
        mockMvc.perform(post("/api/auth/oauth2/authorization/google").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://app.taskapp.test/login?oauth=google_failed"));
        assertThat(capture.lines()).anySatisfy(line -> assertThat(line)
                .startsWith("SECURITY event=GOOGLE_LOGIN_FAILURE intent=connect reason=provider_unavailable"));
    }

    @Test
    void otherPathsUnderTheFlowPrefixAre404() throws Exception {
        for (String path : List.of("/api/auth/oauth2/authorization/github", "/api/auth/oauth2/callback/github",
                "/api/auth/oauth2/anything")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }
}
