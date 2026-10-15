package com.taskapp.admin;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.validation.DirectFieldBindingResult;

import com.taskapp.admin.dto.AdminUserPage;
import com.taskapp.admin.dto.AdminUserQuery;
import com.taskapp.admin.dto.AdminUserResponse;
import com.taskapp.admin.dto.ChangeRoleRequest;
import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.Role;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.ratelimit.RateLimitedException;

/**
 * Admin endpoints at the HTTP layer (specs/roles-admin.md AC7, AC15, AC20): for every endpoint 401 signed out, 403
 * FORBIDDEN for a USER (audited), 403 CSRF_INVALID without the token, and 200/204 for an admin; error shapes of the
 * service's refusals. The rules themselves are tested in AdminUserServiceTest and AdminIntegrationTest.
 */
@WebMvcTest(controllers = AdminUserController.class)
@Import({SecurityConfig.class, SessionAuthenticator.class, UnchangedSessionUserLookup.class})
class AdminUserControllerTest {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin@example.com", "Admin");
    private static final AuthenticatedUser BOB = new AuthenticatedUser(2L, "bob@example.com", "Bob");
    private static final AdminUserResponse ALICE = new AdminUserResponse(42L, "alice@example.com", "Alice",
            Instant.parse("2026-10-15T09:12:44.123456Z"), true, false, Role.USER, false);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUserService service;

    private final SecurityLogCapture capture = new SecurityLogCapture();

    @BeforeEach
    void start() {
        capture.start();
    }

    @AfterEach
    void stop() {
        capture.stop();
    }

    private static RequestPostProcessor as(AuthenticatedUser user, Role role) {
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(user, null, role.authorities()));
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** Every admin endpoint: name, request (without CSRF), whether it changes something. */
    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("list", get("/api/admin/users"), false),
                Arguments.of("disable", post("/api/admin/users/42/disable"), true),
                Arguments.of("enable", post("/api/admin/users/42/enable"), true),
                Arguments.of("revoke_sessions", post("/api/admin/users/42/revoke-sessions"), true),
                Arguments.of("change_role", put("/api/admin/users/42/role").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"), true),
                Arguments.of("delete", delete("/api/admin/users/42"), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void signedOutGets401(String action, MockHttpServletRequestBuilder request, boolean changes) throws Exception {
        mockMvc.perform(request.with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void normalUserGets403ForbiddenAndTheRefusalIsAudited(String action, MockHttpServletRequestBuilder request,
            boolean changes) throws Exception {
        mockMvc.perform(request.with(csrf()).with(as(BOB, Role.USER)).with(from("10.5.0.1")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("Access denied"));

        verifyNoInteractions(service);
        String target = changes ? "42" : "-";
        String path = request.buildRequest(new MockServletContext()).getRequestURI();
        assertThat(capture.lines()).containsExactly("SECURITY event=ADMIN_ACCESS_DENIED actor_id=2 target_id="
                + target + " action=" + action + " reason=not_admin email=bob@example.com ip=10.5.0.1 path=" + path);
        assertThat(capture.events().get(0).getLevel().toString()).isEqualTo("WARN");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void changesWithoutCsrfGet403CsrfInvalid(String action, MockHttpServletRequestBuilder request, boolean changes)
            throws Exception {
        if (!changes) {
            return;
        }
        mockMvc.perform(request.with(as(ADMIN, Role.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(service);
    }

    @Test
    void listReturnsThePageForAnAdmin() throws Exception {
        when(service.list(any())).thenReturn(new AdminUserPage(List.of(ALICE), 0, 20, 1, 1));

        mockMvc.perform(get("/api/admin/users").with(as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"content":[{"id":42,"email":"alice@example.com","displayName":"Alice",
                          "createdAt":"2026-10-15T09:12:44.123456Z","emailVerified":true,"googleConnected":false,
                          "role":"USER","disabled":false}],
                         "page":0,"size":20,"totalElements":1,"totalPages":1}""", true));

        verify(service).list(new AdminUserQuery("", 0, 20));
    }

    @Test
    void listPassesTheQueryParametersAndASuperAdminMayListToo() throws Exception {
        when(service.list(any())).thenReturn(new AdminUserPage(List.of(), 3, 5, 0, 0));

        mockMvc.perform(get("/api/admin/users").param("q", "  jo%_ ").param("page", "3").param("size", "5")
                        .with(as(ADMIN, Role.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalPages").value(0));

        verify(service).list(new AdminUserQuery("jo%_", 3, 5));
    }

    @Test
    void listWithANonIntegerPageIsMalformed() throws Exception {
        mockMvc.perform(get("/api/admin/users").param("page", "abc").with(as(ADMIN, Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verifyNoInteractions(service);
    }

    @Test
    void listValidationErrorsAreReportedPerField() throws Exception {
        AdminUserQuery query = new AdminUserQuery("x", -1, 0);
        DirectFieldBindingResult errors = new DirectFieldBindingResult(query, "adminUserQuery");
        errors.rejectValue("size", "Min", "Size must be between 1 and 100");
        errors.rejectValue("page", "Min", "Page must be 0 or more");
        when(service.list(any())).thenThrow(new InvalidFieldsException(errors));

        mockMvc.perform(get("/api/admin/users").param("page", "-1").param("size", "0").with(as(ADMIN, Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.errors[1].field").value("size"))
                .andExpect(jsonPath("$.errors[1].code").value("OUT_OF_RANGE"));
    }

    @Test
    void disableEnableAndRevokeReturnTheUser() throws Exception {
        when(service.disable(ADMIN, 42L)).thenReturn(ALICE);
        when(service.enable(ADMIN, 42L)).thenReturn(ALICE);
        when(service.revokeSessions(ADMIN, 42L)).thenReturn(ALICE);

        for (String action : List.of("disable", "enable", "revoke-sessions")) {
            mockMvc.perform(post("/api/admin/users/42/" + action).with(csrf()).with(as(ADMIN, Role.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"ignored\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(42))
                    .andExpect(jsonPath("$.role").value("USER"));
        }
        verify(service).disable(ADMIN, 42L);
        verify(service).enable(ADMIN, 42L);
        verify(service).revokeSessions(ADMIN, 42L);
    }

    @Test
    void changeRolePassesTheBodyAndDeleteIs204() throws Exception {
        when(service.changeRole(eq(ADMIN), eq(42L), any())).thenReturn(ALICE);

        mockMvc.perform(put("/api/admin/users/42/role").with(csrf()).with(as(ADMIN, Role.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/users/42").with(csrf()).with(as(ADMIN, Role.SUPER_ADMIN)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).changeRole(ADMIN, 42L, new ChangeRoleRequest("ADMIN"));
        verify(service).delete(ADMIN, 42L);
    }

    @Test
    void changeRoleWithoutAJsonBodyIsMalformed() throws Exception {
        mockMvc.perform(put("/api/admin/users/42/role").with(csrf()).with(as(ADMIN, Role.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void changeRoleValidationErrorIsInvalidRole() throws Exception {
        ChangeRoleRequest body = new ChangeRoleRequest("SUPER_ADMIN");
        DirectFieldBindingResult errors = new DirectFieldBindingResult(body, "changeRoleRequest");
        errors.rejectValue("role", "InvalidRole", "Role must be USER or ADMIN");
        when(service.changeRole(any(), anyLong(), any())).thenThrow(new InvalidFieldsException(errors));

        mockMvc.perform(put("/api/admin/users/42/role").with(csrf()).with(as(ADMIN, Role.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("role"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_ROLE"))
                .andExpect(jsonPath("$.errors[0].message").value("Role must be USER or ADMIN"));
    }

    @Test
    void nonIntegerIdIsMalformed() throws Exception {
        mockMvc.perform(post("/api/admin/users/abc/disable").with(csrf()).with(as(ADMIN, Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.instance").value("/api/admin/users/abc/disable"));
        verifyNoInteractions(service);
    }

    @Test
    void refusalsHaveTheirCodes() throws Exception {
        when(service.disable(ADMIN, 1L)).thenThrow(new CannotModifySelfException());
        when(service.disable(ADMIN, 2L)).thenThrow(new InsufficientRoleException());
        when(service.disable(ADMIN, 3L)).thenThrow(new UserNotFoundException());
        when(service.disable(ADMIN, 4L)).thenThrow(new NotAdminException());
        when(service.disable(ADMIN, 5L)).thenThrow(new ActorDisabledException());

        expectProblem(1, 409, "CANNOT_MODIFY_SELF", "You can't do this to your own account.");
        expectProblem(2, 403, "INSUFFICIENT_ROLE", "You don't have permission to do this.");
        expectProblem(3, 404, "USER_NOT_FOUND", "This user no longer exists.");
        expectProblem(4, 403, "FORBIDDEN", "Access denied");
        expectProblem(5, 401, "ACCOUNT_DISABLED", "This account has been disabled.");
    }

    private void expectProblem(long id, int status, String code, String detail) throws Exception {
        mockMvc.perform(post("/api/admin/users/" + id + "/disable").with(csrf()).with(as(ADMIN, Role.ADMIN)))
                .andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value("/api/admin/users/" + id + "/disable"));
    }

    @Test
    void overTheAdminLimitIs429WithRetryAfterAndLogged() throws Exception {
        doThrow(new RateLimitedException(Duration.ofSeconds(37), RateLimitedException.Limit.ADMIN_ACTION_USER,
                "admin@example.com")).when(service).delete(ADMIN, 42L);

        mockMvc.perform(delete("/api/admin/users/42").with(csrf()).with(as(ADMIN, Role.ADMIN)).with(from("10.5.0.9")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(capture.lines()).containsExactly("SECURITY event=RATE_LIMITED limit=admin-action-user"
                + " email=admin@example.com ip=10.5.0.9 path=/api/admin/users/42");
    }
}
