package com.taskapp.auth;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.UnchangedSessionUserLookup;

/** specs/google-sign-in.md "Optional feature": without GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET (the test default). */
@WebMvcTest(controllers = ConnectionsController.class)
@Import({SecurityConfig.class, SessionAuthenticator.class, UnchangedSessionUserLookup.class})
class GoogleFeatureOffControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @Test
    void providersSaysGoogleIsOff() throws Exception {
        mockMvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"google\":false}", true));
    }

    @Test
    void flowEndpointsAre404() throws Exception {
        mockMvc.perform(post("/api/auth/oauth2/authorization/google").param("intent", "login").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/auth/oauth2/callback/google").param("code", "c").param("state", "s"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // CSRF is still checked first.
        mockMvc.perform(post("/api/auth/oauth2/authorization/google"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void disconnectIs404() throws Exception {
        var principal = new AuthenticatedUser(1L, "bob@example.com", "Bob");
        mockMvc.perform(delete("/api/account/connections/google").with(csrf()).with(signedInSession(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verifyNoInteractions(accountService);
    }
}
