package com.taskapp.notification;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.validation.MapBindingResult;

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.UnchangedSessionUserLookup;

/** Contract 0.14.0 notifications at the HTTP layer: paths, status codes, error shapes, session and CSRF. */
@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, UnchangedSessionUserLookup.class})
class NotificationControllerTest {

    private static final long USER = 7L;
    private static final NotificationResponse N = new NotificationResponse(41L, "REMINDER", false,
            Instant.parse("2026-10-21T06:30:01.120Z"), false, "Buy milk", LocalDate.parse("2026-10-21"),
            LocalTime.of(9, 30), 12L, 3L);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService service;

    private static RequestPostProcessor signedIn() {
        var principal = new AuthenticatedUser(USER, "alice@example.com", "Alice");
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    @Test
    void listIsAPageInContractShape() throws Exception {
        when(service.list(USER, null, null)).thenReturn(new NotificationPage(List.of(N), "abc"));

        mockMvc.perform(get("/api/notifications").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items":[{"id":41,"type":"REMINDER","read":false,"createdAt":"2026-10-21T06:30:01.120Z",
                          "late":false,"title":"Buy milk","dueDate":"2026-10-21","dueTime":"09:30","taskId":12,
                          "listId":3}],"nextCursor":"abc"}""", true));
    }

    @Test
    void aNotificationWhoseTaskIsGoneHasNullLinksAndATimeIsNullForDateOnly() throws Exception {
        var gone = new NotificationResponse(42L, "REMINDER", true, Instant.parse("2026-10-21T06:30:01Z"), true, "X",
                LocalDate.parse("2026-10-21"), null, null, null);
        when(service.list(USER, null, null)).thenReturn(new NotificationPage(List.of(gone), null));

        mockMvc.perform(get("/api/notifications").with(signedIn()))
                .andExpect(jsonPath("$.items[0].taskId").value((Object) null))
                .andExpect(jsonPath("$.items[0].listId").value((Object) null))
                .andExpect(jsonPath("$.items[0].dueTime").value((Object) null))
                .andExpect(jsonPath("$.items[0].read").value(true))
                .andExpect(jsonPath("$.items[0].late").value(true))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
    }

    @Test
    void limitAndCursorAreHandedToTheService() throws Exception {
        when(service.list(eq(USER), eq("5"), eq("cur"))).thenReturn(new NotificationPage(List.of(), null));

        mockMvc.perform(get("/api/notifications?limit=5&cursor=cur&limit=9").with(signedIn()))
                .andExpect(status().isOk());
        verify(service).list(USER, "5", "cur");
    }

    @Test
    void validationErrorsFromTheServiceAreFieldErrors() throws Exception {
        var errors = new MapBindingResult(new HashMap<>(), "notificationQuery");
        errors.rejectValue("limit", "Min", "Limit must be between 1 and 50");
        errors.rejectValue("cursor", "InvalidCursor", "The list changed, reload from the first page");
        when(service.list(eq(USER), any(), any())).thenThrow(new InvalidFieldsException(errors));

        mockMvc.perform(get("/api/notifications?limit=0&cursor=x").with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("cursor"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.errors[1].field").value("limit"))
                .andExpect(jsonPath("$.errors[1].code").value("OUT_OF_RANGE"));
    }

    @Test
    void countIsAnObjectWithCount() throws Exception {
        when(service.count(USER)).thenReturn(new NotificationCount(3));

        mockMvc.perform(get("/api/notifications/count").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"count\":3}", true));
    }

    @Test
    void readReturnsTheNotification() throws Exception {
        when(service.markRead(USER, 41L)).thenReturn(new NotificationResponse(41L, "REMINDER", true,
                N.createdAt(), false, "Buy milk", N.dueDate(), N.dueTime(), 12L, 3L));

        mockMvc.perform(post("/api/notifications/41/read").with(signedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(41))
                .andExpect(jsonPath("$.read").value(true));
    }

    @Test
    void anUnknownOrForeignNotificationIs404WithItsOwnCode() throws Exception {
        when(service.markRead(USER, 99L)).thenThrow(new NotificationNotFoundException());

        mockMvc.perform(post("/api/notifications/99/read").with(signedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Notification not found"))
                .andExpect(jsonPath("$.type").value("about:blank"));
    }

    @Test
    void aNonIntegerIdIsMalformed() throws Exception {
        mockMvc.perform(post("/api/notifications/abc/read").with(signedIn()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void readAllIs204() throws Exception {
        mockMvc.perform(post("/api/notifications/read-all").with(signedIn()).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(service).markAllRead(USER);
    }

    @Test
    void everyEndpointNeedsASessionAndWritesNeedCsrf() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications/count")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/1/read").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/read-all").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/1/read").with(signedIn()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(post("/api/notifications/read-all").with(signedIn()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(service);
    }
}
