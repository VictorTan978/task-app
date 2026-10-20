package com.taskapp.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.ratelimit.RateLimitedException;
import com.taskapp.security.Role;

/** Per-IP limits (real limiter, small limits) and the 429 response shape. Each test uses its own client IP. */
@WebMvcTest(controllers = AuthController.class, properties = {
        "app.rate-limit.login-per-ip-per-minute=3",
        "app.rate-limit.register-per-ip-per-hour=2"})
@Import({SecurityConfig.class, SessionAuthenticator.class, UnchangedSessionUserLookup.class})
class AuthRateLimitControllerTest {

    private static final UserResponse BOB = new UserResponse(1L, "bob@example.com", "Bob", false, true, List.of(), Role.USER);
    private static final String LOGIN = "{\"email\":\"bob@example.com\",\"password\":\"correct-horse\"}";
    private static final String REGISTER =
            "{\"displayName\":\"Bob\",\"email\":\"bob@example.com\",\"password\":\"correct-horse\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MockHttpServletRequestBuilder postJson(String path, String body, String ip) {
        return post(path).with(csrf()).with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void loginOverPerIpLimitIs429WithRetryAfterAndProblemBody() throws Exception {
        when(authService.login(any())).thenReturn(BOB);
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.0.0.1")).andExpect(status().isOk());
        }

        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.0.0.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Too many attempts. Please try again later."))
                .andExpect(jsonPath("$.instance").value("/api/auth/login"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.0.0.2")).andExpect(status().isOk());
    }

    @Test
    void invalidLoginBodiesCountTowardsPerIpLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(postJson("/api/auth/login", "{}", "10.0.1.1")).andExpect(status().isBadRequest());
        }

        mockMvc.perform(postJson("/api/auth/login", "{}", "10.0.1.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void requestsWithoutCsrfAreRejectedBeforeCounting() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login").with(from("10.0.2.1"))
                    .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                    .andExpect(status().isForbidden());
        }
        when(authService.login(any())).thenReturn(BOB);
        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.0.2.1")).andExpect(status().isOk());
    }

    @Test
    void registerOverPerIpLimitIs429AndDoesNotReachService() throws Exception {
        when(authService.register(any())).thenReturn(BOB);
        mockMvc.perform(postJson("/api/auth/register", REGISTER, "10.0.3.1")).andExpect(status().isCreated());
        mockMvc.perform(postJson("/api/auth/register", "{}", "10.0.3.1")).andExpect(status().isBadRequest());

        mockMvc.perform(postJson("/api/auth/register", REGISTER, "10.0.3.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.instance").value("/api/auth/register"));
        verify(authService, times(1)).register(any());

        mockMvc.perform(postJson("/api/auth/register", REGISTER, "10.0.3.2")).andExpect(status().isCreated());
    }

    @Test
    void registerAndLoginLimitsAreSeparate() throws Exception {
        when(authService.register(any())).thenReturn(BOB);
        when(authService.login(any())).thenReturn(BOB);
        mockMvc.perform(postJson("/api/auth/register", REGISTER, "10.0.4.1")).andExpect(status().isCreated());
        mockMvc.perform(postJson("/api/auth/register", REGISTER, "10.0.4.1")).andExpect(status().isCreated());
        mockMvc.perform(postJson("/api/auth/register", REGISTER, "10.0.4.1")).andExpect(status().isTooManyRequests());

        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.0.4.1")).andExpect(status().isOk());
    }

    @Test
    void perEmailLimitFromServiceIsRenderedAs429() throws Exception {
        when(authService.login(any())).thenThrow(new RateLimitedException(Duration.ofSeconds(839).plusMillis(10),
                RateLimitedException.Limit.LOGIN_EMAIL, "bob@example.com"));

        mockMvc.perform(postJson("/api/auth/login", LOGIN, "10.0.5.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "840"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.detail").value("Too many attempts. Please try again later."));
    }
}
