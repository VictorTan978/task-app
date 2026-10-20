package com.taskapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import tools.jackson.databind.ObjectMapper;
import com.taskapp.security.audit.SecurityLogCapture;

/** No endpoint currently answers a plain 403, so ACCESS_DENIED is checked on the handler directly. */
class ProblemAccessDeniedHandlerLoggingTest {

    private final SecurityLogCapture capture = new SecurityLogCapture();
    private final ProblemAccessDeniedHandler handler =
            new ProblemAccessDeniedHandler(new ProblemResponseWriter(new ObjectMapper()));

    @BeforeEach
    void start() {
        capture.start();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(1L, "bob@example.com", "Bob"), null, List.of()));
    }

    @AfterEach
    void stop() {
        capture.stop();
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/tasks/7");
        request.setRemoteAddr("10.2.0.1");
        return request;
    }

    @Test
    void accessDenied() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.handle(request(), response, new AccessDeniedException("nope"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(capture.lines()).containsExactly(
                "SECURITY event=ACCESS_DENIED email=bob@example.com ip=10.2.0.1 path=/api/tasks/7");
    }

    @Test
    void csrfIsDistinguishedAndTokenValuesAreNotLogged() throws Exception {
        DefaultCsrfToken expected = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected-token-value");
        handler.handle(request(), new MockHttpServletResponse(),
                new InvalidCsrfTokenException(expected, "actual-token-value"));

        assertThat(capture.lines()).containsExactly(
                "SECURITY event=CSRF_REJECTED email=bob@example.com ip=10.2.0.1 path=/api/tasks/7");
    }
}
