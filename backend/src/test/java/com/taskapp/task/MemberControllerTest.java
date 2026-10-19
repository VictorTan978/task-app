package com.taskapp.task;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.security.ratelimit.RateLimitedException;
import com.taskapp.task.dto.CreateInvitationRequest;
import com.taskapp.task.dto.ListInvitationResponse;
import com.taskapp.task.dto.MemberResponse;

/** {@code /api/lists/{id}/members}, {@code /leave}, {@code /invitations} (contract 0.13.0). */
@WebMvcTest(MemberController.class)
@Import({SecurityConfig.class, UnchangedSessionUserLookup.class})
class MemberControllerTest {

    private static final long USER = 7L;
    private static final Instant AT = Instant.parse("2026-10-19T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private InvitationService invitationService;

    private static RequestPostProcessor signedIn() {
        var principal = new AuthenticatedUser(USER, "ana@example.com", "Ana");
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.with(signedIn()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void membersInContractShape() throws Exception {
        when(memberService.members(USER, 8L)).thenReturn(List.of(
                new MemberResponse(7L, "Ana", "ana@example.com", ListRole.OWNER, AT),
                new MemberResponse(9L, "Bob", "bob@example.com", ListRole.MEMBER, AT.plusMillis(500))));

        mockMvc.perform(get("/api/lists/8/members").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"userId":7,"displayName":"Ana","email":"ana@example.com","role":"OWNER",
                          "joinedAt":"2026-10-19T10:00:00Z"},
                         {"userId":9,"displayName":"Bob","email":"bob@example.com","role":"MEMBER",
                          "joinedAt":"2026-10-19T10:00:00.500Z"}]""", true));
    }

    @Test
    void removeAndLeaveAre204() throws Exception {
        mockMvc.perform(delete("/api/lists/8/members/9").with(signedIn()).with(csrf()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        mockMvc.perform(post("/api/lists/8/leave").with(signedIn()).with(csrf()))
                .andExpect(status().isNoContent());
        verify(memberService).remove(USER, 8L, 9L);
        verify(memberService).leave(USER, 8L);
    }

    @Test
    void sharingErrorsInContractShape() throws Exception {
        doThrow(new ListNotFoundException()).when(memberService).remove(USER, 99L, 9L);
        doThrow(new OwnerOnlyException()).when(memberService).remove(USER, 21L, 9L);
        doThrow(new OwnerCannotLeaveException()).when(memberService).leave(USER, 8L);
        doThrow(new MemberNotFoundException()).when(memberService).remove(USER, 8L, 42L);
        doThrow(new InvitationNotFoundException()).when(invitationService).cancel(USER, 8L, 31L);

        expect(delete("/api/lists/99/members/9"), 404, "Not Found", "LIST_NOT_FOUND", "List not found");
        expect(delete("/api/lists/21/members/9"), 403, "Forbidden", "OWNER_ONLY", "Only the list owner can do this");
        expect(post("/api/lists/8/leave"), 409, "Conflict", "OWNER_CANNOT_LEAVE", "The owner can't leave the list");
        expect(delete("/api/lists/8/members/42"), 404, "Not Found", "MEMBER_NOT_FOUND", "Member not found");
        expect(delete("/api/lists/8/invitations/31"), 404, "Not Found", "INVITATION_NOT_FOUND",
                "This invitation is no longer valid");
    }

    private void expect(MockHttpServletRequestBuilder request, int status, String title, String code, String detail)
            throws Exception {
        String path = request.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI();
        mockMvc.perform(request.with(signedIn()).with(csrf()))
                .andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                        + ",\"detail\":\"" + detail + "\",\"instance\":\"" + path + "\",\"code\":\"" + code + "\"}",
                        true));
    }

    @Test
    void nonIntegerIdsAreMalformedBeforeAnything() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(get("/api/lists/abc/members").with(signedIn()),
                delete("/api/lists/8/members/abc").with(signedIn()).with(csrf()),
                post("/api/lists/abc/leave").with(signedIn()).with(csrf()),
                delete("/api/lists/8/invitations/x").with(signedIn()).with(csrf()),
                json(post("/api/lists/abc/invitations"), "{\"email\":\"\"}"))) {
            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        verifyNoInteractions(memberService, invitationService);
    }

    @Test
    void inviteIs201WithoutLocationAndPassesTheNormalizedEmailAndCallersEmail() throws Exception {
        when(invitationService.invite(eq(USER), eq("ana@example.com"), eq(8L), any()))
                .thenReturn(new ListInvitationResponse(31L, "carol@example.com", AT, AT.plus(Duration.ofDays(14))));

        mockMvc.perform(json(post("/api/lists/8/invitations"), "{\"email\":\"  Carol@Example.COM \",\"id\":5}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().json("""
                        {"id":31,"email":"carol@example.com","invitedAt":"2026-10-19T10:00:00Z",
                         "expiresAt":"2026-11-02T10:00:00Z"}""", true));
        verify(invitationService).invite(USER, "ana@example.com", 8L, new CreateInvitationRequest("carol@example.com"));
    }

    @Test
    void inviteValidationUsesTheRegisterRules() throws Exception {
        // A valid address (64-character local part, labels under 64) that is longer than 254 characters.
        String tooLong = "a".repeat(60) + "@" + ("b".repeat(60) + ".").repeat(4) + "io";
        String[][] cases = {
                {"{}", "REQUIRED", "Email is required"},
                {"{\"email\":\"   \"}", "REQUIRED", "Email is required"},
                {"{\"email\":\"not-an-email\"}", "INVALID_EMAIL", "Enter a valid email address"},
                {"{\"email\":\"" + tooLong + "\"}", "TOO_LONG", "Email must be at most 254 characters"}};
        for (String[] c : cases) {
            mockMvc.perform(json(post("/api/lists/8/invitations"), c[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("email"))
                    .andExpect(jsonPath("$.errors[0].code").value(c[1]))
                    .andExpect(jsonPath("$.errors[0].message").value(c[2]));
        }
        for (String body : List.of("{\"email\":5}", "{\"email\":[\"a@b.c\"]}", "nope")) {
            mockMvc.perform(json(post("/api/lists/8/invitations"), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.errors").doesNotExist());
        }
        verifyNoInteractions(invitationService);
    }

    @Test
    void inviteConflictsAndRateLimitInContractShape() throws Exception {
        when(invitationService.invite(anyLong(), anyString(), eq(8L), any()))
                .thenThrow(SharingConflictException.alreadyInvited());
        when(invitationService.invite(anyLong(), anyString(), eq(3L), any()))
                .thenThrow(PersonalListException.share());
        when(invitationService.invite(anyLong(), anyString(), eq(9L), any()))
                .thenThrow(new RateLimitedException(Duration.ofSeconds(90), RateLimitedException.Limit.INVITE_USER,
                        "ana@example.com"));

        mockMvc.perform(json(post("/api/lists/8/invitations"), "{\"email\":\"c@x.io\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Conflict","status":409,
                         "detail":"This email address already has a pending invitation",
                         "instance":"/api/lists/8/invitations","code":"ALREADY_INVITED"}""", true));
        mockMvc.perform(json(post("/api/lists/3/invitations"), "{\"email\":\"c@x.io\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERSONAL_LIST"))
                .andExpect(jsonPath("$.detail").value("Your personal list can't be shared"));
        mockMvc.perform(json(post("/api/lists/9/invitations"), "{\"email\":\"c@x.io\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "90"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void pendingInvitationsForTheOwnerAnd403ForAMember() throws Exception {
        when(invitationService.pending(USER, 8L)).thenReturn(List.of());
        when(invitationService.pending(USER, 21L)).thenThrow(new OwnerOnlyException());

        mockMvc.perform(get("/api/lists/8/invitations").with(signedIn()))
                .andExpect(status().isOk()).andExpect(content().json("[]", true));
        mockMvc.perform(get("/api/lists/21/invitations").with(signedIn()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_ONLY"));
    }

    @Test
    void anonymousIs401AndWritesNeedCsrf() throws Exception {
        mockMvc.perform(get("/api/lists/8/members")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/lists/8/leave").with(signedIn()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(post("/api/lists/8/invitations").with(signedIn()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"c@x.io\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(memberService, invitationService);
    }
}
