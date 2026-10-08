package com.taskapp.task;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.TaskResponse;

@WebMvcTest(TaskController.class)
@Import(SecurityConfig.class)
class TaskControllerTest {

    private static final long OWNER = 7L;
    private static final Instant CREATED = Instant.parse("2026-10-08T09:15:02.123456Z");
    private static final TaskResponse MILK = new TaskResponse(12L, "Buy milk", null, false,
            LocalDate.parse("2026-10-10"), CREATED, CREATED);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskService taskService;

    private static RequestPostProcessor signedIn() {
        var principal = new AuthenticatedUser(OWNER, "alice@example.com", "Alice");
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.with(signedIn()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---- list ----

    @Test
    void listReturnsBareArrayInContractShape() throws Exception {
        when(taskService.list(OWNER, TaskFilter.ALL)).thenReturn(List.of(MILK));

        mockMvc.perform(get("/api/tasks").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        [{"id":12,"title":"Buy milk","description":null,"done":false,"dueDate":"2026-10-10",
                          "createdAt":"2026-10-08T09:15:02.123456Z","updatedAt":"2026-10-08T09:15:02.123456Z"}]""",
                        true));
    }

    @Test
    void listPassesKnownFilters() throws Exception {
        mockMvc.perform(get("/api/tasks?filter=active").with(signedIn())).andExpect(status().isOk());
        verify(taskService).list(OWNER, TaskFilter.ACTIVE);

        mockMvc.perform(get("/api/tasks?filter=done").with(signedIn())).andExpect(status().isOk());
        verify(taskService).list(OWNER, TaskFilter.DONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/tasks", "/api/tasks?filter=", "/api/tasks?filter=foo", "/api/tasks?filter=Done",
            "/api/tasks?filter=all"})
    void missingOrUnknownFilterMeansAll(String url) throws Exception {
        when(taskService.list(OWNER, TaskFilter.ALL)).thenReturn(List.of());

        mockMvc.perform(get(url).with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", true));

        verify(taskService).list(OWNER, TaskFilter.ALL);
    }

    // ---- create ----

    @Test
    void createReturns201WithLocationAndStrippedInput() throws Exception {
        when(taskService.create(eq(OWNER), any())).thenReturn(MILK);

        mockMvc.perform(json(post("/api/tasks"), """
                        {"title":"  Buy milk ","description":"   ","dueDate":"2026-10-10",
                         "done":true,"id":99,"ownerId":1,"createdAt":"2020-01-01T00:00:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/tasks/12"))
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        verify(taskService).create(OWNER, new CreateTaskRequest("Buy milk", null, LocalDate.parse("2026-10-10")));
    }

    @Test
    void createAcceptsLimitsAfterStripping() throws Exception {
        when(taskService.create(eq(OWNER), any())).thenReturn(MILK);
        String title = "t".repeat(200);
        String description = "d".repeat(2000);

        mockMvc.perform(json(post("/api/tasks"),
                        "{\"title\":\"  " + title + "\\n\",\"description\":\"\\t" + description + "  \"}"))
                .andExpect(status().isCreated());

        verify(taskService).create(OWNER, new CreateTaskRequest(title, description, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"title\":null}", "{\"title\":\"\"}", "{\"title\":\"   \\t\\n \"}"})
    void createRequiresTitle(String body) throws Exception {
        mockMvc.perform(json(post("/api/tasks"), body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.instance").value("/api/tasks"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("title"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[0].message").value("Title is required"));

        verifyNoInteractions(taskService);
    }

    @Test
    void createReportsTooLongFieldsSortedByName() throws Exception {
        mockMvc.perform(json(post("/api/tasks"),
                        "{\"title\":\"" + "t".repeat(201) + "\",\"description\":\"" + "d".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.errors[0].message").value("Description must be 2,000 characters or fewer"))
                .andExpect(jsonPath("$.errors[1].field").value("title"))
                .andExpect(jsonPath("$.errors[1].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.errors[1].message").value("Title must be 200 characters or fewer"));

        verifyNoInteractions(taskService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30", "10/08/2026", "2026-13-01", "tomorrow"})
    void createWithInvalidDueDateIsMalformed(String dueDate) throws Exception {
        mockMvc.perform(json(post("/api/tasks"), "{\"title\":\"x\",\"dueDate\":\"" + dueDate + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Malformed request body"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        verifyNoInteractions(taskService);
    }

    @Test
    void createWithBrokenJsonIsMalformed() throws Exception {
        mockMvc.perform(json(post("/api/tasks"), "{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    // ---- get ----

    @Test
    void getReturnsTask() throws Exception {
        when(taskService.get(OWNER, 12L)).thenReturn(MILK);

        mockMvc.perform(get("/api/tasks/12").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Buy milk"));
    }

    @Test
    void missingOrForeignTaskIs404Problem() throws Exception {
        when(taskService.get(OWNER, 42L)).thenThrow(new TaskNotFoundException());

        mockMvc.perform(get("/api/tasks/42").with(signedIn()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Not Found","status":404,"detail":"Task not found",
                         "instance":"/api/tasks/42","code":"NOT_FOUND"}""", true));
    }

    @Test
    void nonIntegerIdIs400Malformed() throws Exception {
        mockMvc.perform(get("/api/tasks/abc").with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Bad Request","status":400,
                         "detail":"Failed to convert 'id' with value 'abc'","instance":"/api/tasks/abc",
                         "code":"MALFORMED_REQUEST"}""", true));

        mockMvc.perform(delete("/api/tasks/1.5").with(signedIn()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(taskService);
    }

    // ---- put ----

    @Test
    void putPassesFullReplacementWithClearedFields() throws Exception {
        when(taskService.replace(eq(OWNER), eq(12L), any())).thenReturn(MILK);

        mockMvc.perform(json(put("/api/tasks/12"), """
                        {"title":" Buy oat milk ","description":null,"done":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12));

        verify(taskService).replace(OWNER, 12L, new ReplaceTaskRequest("Buy oat milk", null, null, true));
    }

    @Test
    void putRequiresTitleAndDone() throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\" \",\"description\":\"" + "d".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.errors[1].field").value("done"))
                .andExpect(jsonPath("$.errors[1].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[1].message").value("Done is required"))
                .andExpect(jsonPath("$.errors[2].field").value("title"))
                .andExpect(jsonPath("$.errors[2].code").value("REQUIRED"));

        verifyNoInteractions(taskService);
    }

    @Test
    void putWithWrongTypeIsMalformed() throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"x\",\"done\":\"maybe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        verifyNoInteractions(taskService);
    }

    @Test
    void putOnMissingTaskIs404() throws Exception {
        when(taskService.replace(eq(OWNER), eq(42L), any())).thenThrow(new TaskNotFoundException());

        mockMvc.perform(json(put("/api/tasks/42"), "{\"title\":\"x\",\"done\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- patch ----

    @Test
    void patchSetsDone() throws Exception {
        when(taskService.setDone(OWNER, 12L, true)).thenReturn(MILK);

        mockMvc.perform(json(patch("/api/tasks/12"), "{\"done\":true,\"title\":\"ignored\"}"))
                .andExpect(status().isOk());

        verify(taskService).setDone(OWNER, 12L, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"done\":null}"})
    void patchRequiresDone(String body) throws Exception {
        mockMvc.perform(json(patch("/api/tasks/12"), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("done"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[0].message").value("Done is required"));

        verifyNoInteractions(taskService);
    }

    @Test
    void patchOnMissingTaskIs404() throws Exception {
        when(taskService.setDone(OWNER, 42L, false)).thenThrow(new TaskNotFoundException());

        mockMvc.perform(json(patch("/api/tasks/42"), "{\"done\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- delete ----

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/tasks/12").with(signedIn()).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(taskService).delete(OWNER, 12L);
    }

    @Test
    void deleteOnMissingTaskIs404() throws Exception {
        doThrow(new TaskNotFoundException()).when(taskService).delete(OWNER, 42L);

        mockMvc.perform(delete("/api/tasks/42").with(signedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Task not found"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- security ----

    @Test
    void everyEndpointRequiresSignIn() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/tasks"),
                get("/api/tasks/12"),
                post("/api/tasks").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"),
                put("/api/tasks/12").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"done\":false}"),
                patch("/api/tasks/12").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"),
                delete("/api/tasks/12").with(csrf()))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(taskService);
    }

    @Test
    void stateChangingEndpointsRequireCsrf() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"),
                put("/api/tasks/12").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\",\"done\":false}"),
                patch("/api/tasks/12").contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"),
                delete("/api/tasks/12"))) {
            mockMvc.perform(request.with(signedIn()))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        }
        verifyNoInteractions(taskService);
    }

    @Test
    void ownerAlwaysComesFromSession() throws Exception {
        when(taskService.create(anyLong(), any())).thenReturn(MILK);
        when(taskService.setDone(anyLong(), anyLong(), anyBoolean())).thenReturn(MILK);

        mockMvc.perform(json(post("/api/tasks"), "{\"title\":\"x\",\"ownerId\":999}")).andExpect(status().isCreated());
        mockMvc.perform(json(patch("/api/tasks/12"), "{\"done\":true,\"ownerId\":999}")).andExpect(status().isOk());

        verify(taskService).create(eq(OWNER), any());
        verify(taskService).setDone(OWNER, 12L, true);
    }
}
