package com.taskapp.task;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
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
import com.taskapp.task.dto.EmptyTrashResult;
import com.taskapp.task.dto.TaskResponse;
import com.taskapp.task.dto.TrashItem;
import com.taskapp.task.dto.TrashPage;

@WebMvcTest(TrashController.class)
@Import({SecurityConfig.class, UnchangedSessionUserLookup.class})
class TrashControllerTest {

    private static final long USER = 7L;
    private static final Instant CREATED = Instant.parse("2026-10-08T09:15:02.123456Z");
    private static final Instant DELETED = Instant.parse("2026-10-19T08:00:00Z");
    private static final TaskResponse MILK = new TaskResponse(12L, 3L, new TaskResponse.AddedBy("Ana"), "Buy milk",
            null, Priority.NONE, TaskStatus.TODO, List.of(), false, LocalDate.parse("2026-10-10"), null, null, false,
            CREATED, CREATED);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrashService trash;

    @MockitoBean
    private TaskService tasks;

    private static RequestPostProcessor signedIn() {
        var principal = new AuthenticatedUser(USER, "alice@example.com", "Alice");
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    @Test
    void listIsATrashPageInContractShape() throws Exception {
        TrashItem item = new TrashItem(MILK, DELETED, new TrashItem.DeletedBy("Ben"), DELETED.plusSeconds(30 * 86400L));
        when(trash.list(USER, null, null)).thenReturn(new TrashPage(List.of(item), 1, null));

        mockMvc.perform(get("/api/trash").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items":[{"id":12,"listId":3,"addedBy":{"displayName":"Ana"},"title":"Buy milk",
                          "description":null,"priority":"NONE","status":"TODO","tags":[],"done":false,
                          "dueDate":"2026-10-10","dueTime":null,"repeat":null,"myReminder":null,
                          "myReminderPassed":false,"createdAt":"2026-10-08T09:15:02.123456Z",
                          "updatedAt":"2026-10-08T09:15:02.123456Z","deletedAt":"2026-10-19T08:00:00Z",
                          "deletedBy":{"displayName":"Ben"},"purgeAt":"2026-11-18T08:00:00Z"}],
                         "total":1,"nextCursor":null}""", true));
    }

    @Test
    void aDeletedDeleterIsNull() throws Exception {
        TrashItem item = new TrashItem(MILK, DELETED, null, DELETED.plusSeconds(1));
        when(trash.list(USER, null, null)).thenReturn(new TrashPage(List.of(item), 1, null));

        mockMvc.perform(get("/api/trash").with(signedIn()))
                .andExpect(jsonPath("$.items[0].deletedBy").value((Object) null));
    }

    @Test
    void listAndCursorAreHandedToTheServiceAsTheFirstValue() throws Exception {
        when(trash.list(USER, "8", "cur")).thenReturn(new TrashPage(List.of(), 0, null));

        mockMvc.perform(get("/api/trash?list=8&cursor=cur&list=9").with(signedIn())).andExpect(status().isOk());
        verify(trash).list(USER, "8", "cur");
    }

    @Test
    void badCursorIs400AndUnknownListIs404() throws Exception {
        var errors = new MapBindingResult(new HashMap<>(), "trashQuery");
        errors.rejectValue("cursor", "InvalidCursor", "The list changed, reload from the first page");
        when(trash.list(USER, null, "zzz")).thenThrow(new InvalidFieldsException(errors));
        when(trash.list(USER, "99", null)).thenThrow(new ListNotFoundException());

        mockMvc.perform(get("/api/trash?cursor=zzz").with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("cursor"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_CURSOR"));
        mockMvc.perform(get("/api/trash?list=99").with(signedIn()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LIST_NOT_FOUND"));
    }

    @Test
    void restoreAnswersTheTaskAndAMissingOneIs404() throws Exception {
        when(tasks.restore(USER, 12L)).thenReturn(MILK);
        when(tasks.restore(USER, 42L)).thenThrow(new TaskNotFoundException());

        mockMvc.perform(post("/api/trash/12/restore").with(signedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.title").value("Buy milk"));
        mockMvc.perform(post("/api/trash/42/restore").with(signedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/trash/abc/restore").with(signedIn()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void deleteForeverIs204AndAMissingOneIs404() throws Exception {
        doThrow(new TaskNotFoundException()).when(tasks).deleteForever(USER, 42L);

        mockMvc.perform(delete("/api/trash/12").with(signedIn()).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(tasks).deleteForever(USER, 12L);
        mockMvc.perform(delete("/api/trash/42").with(signedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void emptyAnswersTheCountAndTakesAnOptionalList() throws Exception {
        when(trash.empty(USER, null)).thenReturn(new EmptyTrashResult(7));
        when(trash.empty(USER, "8")).thenReturn(new EmptyTrashResult(2));
        when(trash.empty(USER, "99")).thenThrow(new ListNotFoundException());

        mockMvc.perform(post("/api/trash/empty").with(signedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"deleted\":7}", true));
        mockMvc.perform(post("/api/trash/empty?list=8").with(signedIn()).with(csrf()))
                .andExpect(content().json("{\"deleted\":2}", true));
        mockMvc.perform(post("/api/trash/empty?list=99").with(signedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LIST_NOT_FOUND"));
    }

    @Test
    void everyTrashEndpointNeedsSignInAndStateChangesNeedCsrf() throws Exception {
        mockMvc.perform(get("/api/trash")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/trash/12/restore").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/trash/12").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/trash/empty").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/trash/12/restore").with(signedIn())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/trash/12").with(signedIn())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/trash/empty").with(signedIn())).andExpect(status().isForbidden());
        verifyNoInteractions(trash, tasks);
    }
}
