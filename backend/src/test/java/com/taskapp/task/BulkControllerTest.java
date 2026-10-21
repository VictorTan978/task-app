package com.taskapp.task;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.task.dto.BulkResult;
import com.taskapp.task.dto.BulkUndoCompleteResult;
import com.taskapp.task.dto.RestoreTasksResult;
import com.taskapp.task.dto.TaskResponse;
import com.taskapp.task.dto.TaskResult;
import com.taskapp.task.dto.UndoCompleteResult;

/** The bulk endpoints of contract 0.17.0: request checks, error shapes and JSON shapes; the service is a mock. */
@WebMvcTest({TaskController.class, TrashController.class})
@Import({SecurityConfig.class, UnchangedSessionUserLookup.class})
class BulkControllerTest {

    private static final long USER = 7L;
    private static final Instant CREATED = Instant.parse("2026-10-08T09:15:02.123456Z");
    private static final TaskResponse MILK = new TaskResponse(12L, 3L, new TaskResponse.AddedBy("Ana"), "Buy milk",
            null, Priority.NONE, TaskStatus.DONE, List.of(), true, null, null, null, false, CREATED, CREATED);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskService taskService;

    @MockitoBean
    private BulkService bulk;

    @MockitoBean
    private TrashService trash;

    private static RequestPostProcessor signedIn() {
        var principal = new AuthenticatedUser(USER, "alice@example.com", "Alice");
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private org.springframework.test.web.servlet.ResultActions send(String path, String body) throws Exception {
        return mockMvc.perform(post(path).with(signedIn()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void bulkActionIsParsedAndAnswersInContractShape() throws Exception {
        when(bulk.bulk(eq(USER), any(), any())).thenReturn(new BulkResult(1, List.of(TaskResult.of(MILK))));

        send("/api/tasks/bulk", "{\"ids\":[12],\"action\":\"SET_PRIORITY\",\"value\":\"HIGH\"}")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"count":1,"tasks":[{"id":12,"listId":3,"status":"DONE","done":true,"nextTask":null}]}"""));

        ArgumentCaptor<BulkRequests.Parsed> parsed = ArgumentCaptor.forClass(BulkRequests.Parsed.class);
        verify(bulk).bulk(eq(USER), eq(List.of(12L)), parsed.capture());
        org.assertj.core.api.Assertions.assertThat(parsed.getValue().priority()).isEqualTo(Priority.HIGH);
    }

    @Test
    void tagValueIsNormalisedAndMoveNeedsAnInteger() throws Exception {
        when(bulk.bulk(eq(USER), any(), any())).thenReturn(new BulkResult(1, List.of()));
        send("/api/tasks/bulk", "{\"ids\":[12],\"action\":\"ADD_TAG\",\"value\":\"  Home   run \"}")
                .andExpect(status().isOk());
        ArgumentCaptor<BulkRequests.Parsed> parsed = ArgumentCaptor.forClass(BulkRequests.Parsed.class);
        verify(bulk).bulk(eq(USER), any(), parsed.capture());
        org.assertj.core.api.Assertions.assertThat(parsed.getValue().tag()).isEqualTo("Home run");

        send("/api/tasks/bulk", "{\"ids\":[12],\"action\":\"MOVE\",\"value\":\"8\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void invalidRequestsAreRejectedBeforeTheService() throws Exception {
        send("/api/tasks/bulk", "{\"ids\":[1,1],\"action\":\"COMPLETE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("ids"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        send("/api/tasks/bulk", "{\"ids\":[],\"action\":\"SET_STATUS\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("ids"))
                .andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.errors[1].field").value("value"))
                .andExpect(jsonPath("$.errors[1].code").value("REQUIRED"));
        send("/api/tasks/bulk-patch", "{\"items\":[{\"id\":1,\"patch\":{\"status\":\"DONE\",\"done\":false}}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items"))
                .andExpect(jsonPath("$.errors[0].code").value("STATUS_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].message").value("Item 1: Status and done don't match"));
        send("/api/tasks/bulk-undo-complete", "{\"items\":[{\"id\":1,\"status\":\"DONE\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items"))
                .andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));
        send("/api/trash/restore", "{\"ids\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));
        verifyNoInteractions(bulk);
    }

    @Test
    void notFoundAndTooManyCarryFailedIds() throws Exception {
        when(bulk.bulk(eq(USER), any(), any()))
                .thenThrow(new BulkTasksNotFoundException(List.of(41L, 13L)))
                .thenThrow(new BulkTooManyTagsException(List.of(5L)));

        send("/api/tasks/bulk", "{\"ids\":[12,13,41],\"action\":\"COMPLETE\"}")
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Some tasks were not found"))
                .andExpect(jsonPath("$.failedIds[0]").value(13))
                .andExpect(jsonPath("$.failedIds[1]").value(41));
        send("/api/tasks/bulk", "{\"ids\":[5],\"action\":\"ADD_TAG\",\"value\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("tags"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_MANY"))
                .andExpect(jsonPath("$.failedIds[0]").value(5));
    }

    @Test
    void undoCompleteAndRestoreAnswerInContractShape() throws Exception {
        when(bulk.bulkUndoComplete(eq(USER), any())).thenReturn(new BulkUndoCompleteResult(
                List.of(new UndoCompleteResult(MILK, UndoCompleteResult.Outcome.NONE))));
        send("/api/tasks/bulk-undo-complete", "{\"items\":[{\"id\":12}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value(12))
                .andExpect(jsonPath("$.results[0].nextTaskOutcome").value("NONE"));
        when(bulk.restore(eq(USER), any())).thenReturn(new RestoreTasksResult(List.of(MILK)));
        send("/api/trash/restore", "{\"ids\":[12]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].id").value(12));
    }

    @Test
    void csrfAndSignInAreRequired() throws Exception {
        for (String path : List.of("/api/tasks/bulk", "/api/tasks/bulk-patch", "/api/tasks/bulk-undo-complete",
                "/api/trash/restore")) {
            mockMvc.perform(post(path).with(signedIn()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(bulk);
    }
}
