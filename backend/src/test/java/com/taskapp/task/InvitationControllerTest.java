package com.taskapp.task;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.task.dto.InvitationCountResponse;
import com.taskapp.task.dto.MyInvitationResponse;
import com.taskapp.task.dto.TaskListResponse;

/** {@code /api/invitations} (contract 0.13.0): the signed-in user's own invitations. */
@WebMvcTest(InvitationController.class)
@Import({SecurityConfig.class, UnchangedSessionUserLookup.class})
class InvitationControllerTest {

    private static final long USER = 9L;
    private static final Instant AT = Instant.parse("2026-10-19T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvitationService invitationService;

    private static RequestPostProcessor signedIn() {
        var principal = new AuthenticatedUser(USER, "bob@example.com", "Bob");
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    @Test
    void mineAndCountInContractShape() throws Exception {
        when(invitationService.mine(USER)).thenReturn(List.of(new MyInvitationResponse(31L,
                new MyInvitationResponse.ListRef(8L, "Home"), new MyInvitationResponse.InvitedBy("Ana"), AT,
                AT.plus(Duration.ofDays(14)))));
        when(invitationService.count(USER)).thenReturn(new InvitationCountResponse(1));

        mockMvc.perform(get("/api/invitations").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"id":31,"list":{"id":8,"name":"Home"},"invitedBy":{"displayName":"Ana"},
                          "invitedAt":"2026-10-19T10:00:00Z","expiresAt":"2026-11-02T10:00:00Z"}]""", true));
        mockMvc.perform(get("/api/invitations/count").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"count\":1}", true));
    }

    @Test
    void acceptReturnsTheListAndDeclineIs204() throws Exception {
        when(invitationService.accept(USER, 31L))
                .thenReturn(new TaskListResponse(8L, "Home", false, ListRole.MEMBER, 4));

        mockMvc.perform(post("/api/invitations/31/accept").with(signedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"id\":8,\"name\":\"Home\",\"personal\":false,\"role\":\"MEMBER\",\"memberCount\":4}", true));
        mockMvc.perform(post("/api/invitations/31/decline").with(signedIn()).with(csrf()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(invitationService).decline(USER, 31L);
    }

    @Test
    void noLongerValidAndMemberLimitInContractShape() throws Exception {
        when(invitationService.accept(USER, 31L)).thenThrow(new InvitationNotFoundException());
        when(invitationService.accept(USER, 32L)).thenThrow(SharingConflictException.memberLimit());
        doThrow(new InvitationNotFoundException()).when(invitationService).decline(USER, 31L);

        mockMvc.perform(post("/api/invitations/31/accept").with(signedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Not Found","status":404,
                         "detail":"This invitation is no longer valid","instance":"/api/invitations/31/accept",
                         "code":"INVITATION_NOT_FOUND"}""", true));
        mockMvc.perform(post("/api/invitations/31/decline").with(signedIn()).with(csrf()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
        mockMvc.perform(post("/api/invitations/32/accept").with(signedIn()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Conflict","status":409,
                         "detail":"A list can have at most 20 members","instance":"/api/invitations/32/accept",
                         "code":"MEMBER_LIMIT"}""", true));
    }

    @Test
    void malformedIdsAnonymousAndCsrf() throws Exception {
        mockMvc.perform(post("/api/invitations/abc/accept").with(signedIn()).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get("/api/invitations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/invitations/count")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/invitations/31/accept").with(signedIn()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(invitationService);
    }
}
