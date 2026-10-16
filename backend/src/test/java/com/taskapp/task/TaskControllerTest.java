package com.taskapp.task;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static com.taskapp.security.TestSessions.signedInSession;
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
import java.util.HashMap;
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
import org.springframework.validation.MapBindingResult;

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.TaskPage;
import com.taskapp.task.dto.TaskResponse;

@WebMvcTest(TaskController.class)
@Import({SecurityConfig.class, UnchangedSessionUserLookup.class})
class TaskControllerTest {

    private static final long OWNER = 7L;
    private static final Instant CREATED = Instant.parse("2026-10-08T09:15:02.123456Z");
    private static final TaskResponse MILK = new TaskResponse(12L, "Buy milk", null, Priority.NONE, TaskStatus.TODO,
            List.of(), false, LocalDate.parse("2026-10-10"), CREATED, CREATED);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskService taskService;

    private static RequestPostProcessor signedIn() {
        var principal = new AuthenticatedUser(OWNER, "alice@example.com", "Alice");
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.with(signedIn()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---- list ----

    private static TaskListQuery query(TaskFilter filter, String tag) {
        return new TaskListQuery(filter, TaskSort.NEWEST, tag, "", null);
    }

    @Test
    void listReturnsAPageInContractShape() throws Exception {
        when(taskService.list(OWNER, query(TaskFilter.ALL, null))).thenReturn(new TaskPage(List.of(MILK), 120, "AbC-_9"));

        mockMvc.perform(get("/api/tasks").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"items":[{"id":12,"title":"Buy milk","description":null,"priority":"NONE","status":"TODO",
                          "tags":[],"done":false,"dueDate":"2026-10-10",
                          "createdAt":"2026-10-08T09:15:02.123456Z","updatedAt":"2026-10-08T09:15:02.123456Z"}],
                         "total":120,"nextCursor":"AbC-_9"}""",
                        true));
    }

    @Test
    void lastPageHasAnExplicitNullCursor() throws Exception {
        when(taskService.list(OWNER, query(TaskFilter.ALL, null))).thenReturn(TaskPage.empty());

        mockMvc.perform(get("/api/tasks").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"items\":[],\"total\":0,\"nextCursor\":null}", true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"nextCursor\":null")));
    }

    @Test
    void listPassesKnownFilters() throws Exception {
        mockMvc.perform(get("/api/tasks?filter=active").with(signedIn())).andExpect(status().isOk());
        verify(taskService).list(OWNER, query(TaskFilter.ACTIVE, null));

        mockMvc.perform(get("/api/tasks?filter=done").with(signedIn())).andExpect(status().isOk());
        verify(taskService).list(OWNER, query(TaskFilter.DONE, null));

        mockMvc.perform(get("/api/tasks?filter=in_progress").with(signedIn())).andExpect(status().isOk());
        verify(taskService).list(OWNER, query(TaskFilter.IN_PROGRESS, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/tasks", "/api/tasks?filter=", "/api/tasks?filter=foo", "/api/tasks?filter=Done",
            "/api/tasks?filter=all", "/api/tasks?filter=In_progress", "/api/tasks?filter=in-progress"})
    void missingOrUnknownFilterMeansAll(String url) throws Exception {
        when(taskService.list(OWNER, query(TaskFilter.ALL, null))).thenReturn(TaskPage.empty());

        mockMvc.perform(get(url).with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        verify(taskService).list(OWNER, query(TaskFilter.ALL, null));
    }

    @Test
    void listPassesSortSearchAndCursorFirstValues() throws Exception {
        mockMvc.perform(get("/api/tasks").param("filter", "done").param("sort", "title", "due")
                .param("tag", "Work").param("q", "  milk  ", "bread").param("cursor", "abc", "def").with(signedIn()))
                .andExpect(status().isOk());
        verify(taskService).list(OWNER, new TaskListQuery(TaskFilter.DONE, TaskSort.TITLE, "Work", "milk", "abc"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"newest", "oldest", "due", "priority", "title"})
    void knownSortsArePassed(String sort) throws Exception {
        mockMvc.perform(get("/api/tasks").param("sort", sort).with(signedIn())).andExpect(status().isOk());
        verify(taskService).list(OWNER,
                new TaskListQuery(TaskFilter.ALL, TaskSort.valueOf(sort.toUpperCase()), null, "", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "foo", "Due", "TITLE", "created", " newest"})
    void unknownSortMeansNewest(String sort) throws Exception {
        mockMvc.perform(get("/api/tasks").param("sort", sort).with(signedIn())).andExpect(status().isOk());
        verify(taskService).list(OWNER, query(TaskFilter.ALL, null));
    }

    @Test
    void blankSearchIsNoSearch() throws Exception {
        mockMvc.perform(get("/api/tasks").param("q", " \t\u00a0\u3000 ").with(signedIn())).andExpect(status().isOk());
        verify(taskService).list(OWNER, query(TaskFilter.ALL, null));
    }

    @Test
    void invalidCursorAndTooLongSearchAre400SortedByField() throws Exception {
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), "taskListQuery");
        errors.rejectValue("q", "Size", "Search must be at most 100 characters");
        errors.rejectValue("cursor", TaskService.INVALID_CURSOR_CONSTRAINT, "The list changed, reload from the first page");
        when(taskService.list(eq(OWNER), any())).thenThrow(new InvalidFieldsException(errors));

        mockMvc.perform(get("/api/tasks").param("q", "x".repeat(101)).param("cursor", "nope").with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.instance").value("/api/tasks"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("cursor"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.errors[0].message").value("The list changed, reload from the first page"))
                .andExpect(jsonPath("$.errors[1].field").value("q"))
                .andExpect(jsonPath("$.errors[1].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.errors[1].message").value("Search must be at most 100 characters"));
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

        verify(taskService).replace(OWNER, 12L,
                new ReplaceTaskRequest("Buy oat milk", null, null, null, null, true, null));
    }

    /** 0.10.0: done is no longer required. */
    @Test
    void putRequiresTitleButNotDone() throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\" \",\"description\":\"" + "d".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.errors[1].field").value("title"))
                .andExpect(jsonPath("$.errors[1].code").value("REQUIRED"));

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
        when(taskService.setStatus(OWNER, 12L, null, true)).thenReturn(MILK);

        mockMvc.perform(json(patch("/api/tasks/12"), "{\"done\":true,\"title\":\"ignored\",\"tags\":[\"x\"]}"))
                .andExpect(status().isOk());

        verify(taskService).setStatus(OWNER, 12L, null, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"done\":null}", "{\"status\":null}", "{\"done\":null,\"status\":null}"})
    void patchRequiresDoneOrStatus(String body) throws Exception {
        mockMvc.perform(json(patch("/api/tasks/12"), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("done"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[0].message").value("Done or status is required"));

        verifyNoInteractions(taskService);
    }

    @Test
    void patchOnMissingTaskIs404() throws Exception {
        when(taskService.setStatus(OWNER, 42L, null, false)).thenThrow(new TaskNotFoundException());

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
        when(taskService.setStatus(anyLong(), anyLong(), any(), any())).thenReturn(MILK);

        mockMvc.perform(json(post("/api/tasks"), "{\"title\":\"x\",\"ownerId\":999}")).andExpect(status().isCreated());
        mockMvc.perform(json(patch("/api/tasks/12"), "{\"done\":true,\"ownerId\":999}")).andExpect(status().isOk());

        verify(taskService).create(eq(OWNER), any());
        verify(taskService).setStatus(OWNER, 12L, null, true);
    }

    // ---- 0.10.0: priority, status, tags ----

    private static String tagsJson(String... raw) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < raw.length; i++) {
            out.append(i == 0 ? "" : ",").append(raw[i] == null ? "null" : "\"" + raw[i] + "\"");
        }
        return out.append("]").toString();
    }

    private static String putWithTags(String tagsArray) {
        return "{\"title\":\"x\",\"tags\":" + tagsArray + "}";
    }

    @Test
    void listReturnsPriorityStatusAndTags() throws Exception {
        TaskResponse tagged = new TaskResponse(12L, "Buy milk", null, Priority.HIGH, TaskStatus.IN_PROGRESS,
                List.of("Errands", "Home"), false, null, CREATED, CREATED);
        when(taskService.list(OWNER, query(TaskFilter.ALL, null))).thenReturn(new TaskPage(List.of(tagged), 1, null));

        mockMvc.perform(get("/api/tasks").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items":[{"id":12,"title":"Buy milk","description":null,"priority":"HIGH",
                          "status":"IN_PROGRESS","tags":["Errands","Home"],"done":false,"dueDate":null,
                          "createdAt":"2026-10-08T09:15:02.123456Z","updatedAt":"2026-10-08T09:15:02.123456Z"}],
                         "total":1,"nextCursor":null}""",
                        true));
    }

    @Test
    void listPassesTheFirstRawTagValueAndCombinesWithFilter() throws Exception {
        mockMvc.perform(get("/api/tasks?tag=Work").with(signedIn())).andExpect(status().isOk());
        verify(taskService).list(OWNER, query(TaskFilter.ALL, "Work"));

        mockMvc.perform(get("/api/tasks").param("filter", "done").param("tag", " wörk ").with(signedIn()))
                .andExpect(status().isOk());
        verify(taskService).list(OWNER, query(TaskFilter.DONE, " wörk "));

        // Not split on commas, not joined when repeated: the first value as sent.
        mockMvc.perform(get("/api/tasks").param("tag", "a,b").with(signedIn())).andExpect(status().isOk());
        verify(taskService).list(OWNER, query(TaskFilter.ALL, "a,b"));

        mockMvc.perform(get("/api/tasks").param("filter", "in_progress").param("tag", "first", "second").with(signedIn()))
                .andExpect(status().isOk());
        verify(taskService).list(OWNER, query(TaskFilter.IN_PROGRESS, "first"));
    }

    @Test
    void createIgnoresPriorityStatusAndTags() throws Exception {
        when(taskService.create(eq(OWNER), any())).thenReturn(MILK);

        mockMvc.perform(json(post("/api/tasks"), """
                        {"title":"x","priority":"URGENT","status":"done","tags":["a,b",1]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("NONE"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.tags", hasSize(0)));

        verify(taskService).create(OWNER, new CreateTaskRequest("x", null, null));
    }

    @Test
    void putPassesNewFieldsWithRawTags() throws Exception {
        when(taskService.replace(eq(OWNER), eq(12L), any())).thenReturn(MILK);

        mockMvc.perform(json(put("/api/tasks/12"), """
                        {"title":"x","priority":"HIGH","status":"IN_PROGRESS","tags":[" Home ","Errands","errands"]}"""))
                .andExpect(status().isOk());

        verify(taskService).replace(OWNER, 12L, new ReplaceTaskRequest("x", null, null, Priority.HIGH,
                TaskStatus.IN_PROGRESS, null, List.of(" Home ", "Errands", "errands")));
    }

    @Test
    void putWithoutNewFieldsPassesNullsAndEmptyTagsStaysEmpty() throws Exception {
        when(taskService.replace(eq(OWNER), eq(12L), any())).thenReturn(MILK);

        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"x\"}")).andExpect(status().isOk());
        verify(taskService).replace(OWNER, 12L, new ReplaceTaskRequest("x", null, null, null, null, null, null));

        mockMvc.perform(json(put("/api/tasks/12"),
                "{\"title\":\"y\",\"priority\":null,\"status\":null,\"done\":null,\"tags\":null}")).andExpect(status().isOk());
        verify(taskService).replace(OWNER, 12L, new ReplaceTaskRequest("y", null, null, null, null, null, null));

        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"z\",\"tags\":[]}")).andExpect(status().isOk());
        verify(taskService).replace(OWNER, 12L, new ReplaceTaskRequest("z", null, null, null, null, null, List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":\"x\",\"priority\":\"URGENT\"}",
            "{\"title\":\"x\",\"priority\":\"high\"}",
            "{\"title\":\"x\",\"priority\":\"HIGH \"}",
            "{\"title\":\"x\",\"priority\":0}",
            "{\"title\":\"x\",\"priority\":\"\"}",
            "{\"title\":\"x\",\"status\":\"done\"}",
            "{\"title\":\"x\",\"status\":\"In_Progress\"}",
            "{\"title\":\"x\",\"status\":1}",
            "{\"title\":\"x\",\"status\":true}",
            "{\"title\":\"x\",\"tags\":\"Work\"}",
            "{\"title\":\"x\",\"tags\":[1]}",
            "{\"title\":\"x\",\"tags\":[true]}",
            "{\"title\":\"x\",\"tags\":[\"ok\",{\"name\":\"Work\"}]}",
            "{\"title\":\"x\",\"tags\":[[\"Work\"]]}",
            "{\"title\":\"x\",\"tags\":{\"0\":\"Work\"}}"})
    void putWithBadNewFieldIsMalformed(String body) throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Malformed request body"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        verifyNoInteractions(taskService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"IN_PROGRESS\",\"done\":true", "\"TODO\",\"done\":true", "\"DONE\",\"done\":false"})
    void putWithDisagreeingStatusAndDoneIsStatusConflict(String statusAndDone) throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"x\",\"status\":" + statusAndDone + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("status"))
                .andExpect(jsonPath("$.errors[0].code").value("STATUS_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].message").value("Status and done don't match"));

        verifyNoInteractions(taskService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"DONE\",\"done\":true", "\"TODO\",\"done\":false", "\"IN_PROGRESS\",\"done\":false"})
    void putWithAgreeingStatusAndDoneIsAccepted(String statusAndDone) throws Exception {
        when(taskService.replace(eq(OWNER), eq(12L), any())).thenReturn(MILK);

        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"x\",\"status\":" + statusAndDone + "}"))
                .andExpect(status().isOk());
    }

    private void expectTagsError(String tagsArray, String code, String message) throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), putWithTags(tagsArray)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("tags"))
                .andExpect(jsonPath("$.errors[0].code").value(code))
                .andExpect(jsonPath("$.errors[0].message").value(message));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[null]", "[\"\"]", "[\"   \"]", "[\"ok\",\" \\u00a0\\u2003 \"]", "[\"\\u3000\"]",
            "[\"\\t\"]", "[\"\\n \\r\"]"})
    void blankTagIsRequired(String tagsArray) throws Exception {
        expectTagsError(tagsArray, "REQUIRED", "Tags can't be blank");
        verifyNoInteractions(taskService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"a,b\"]", "[\",\"]", "[\"Work,\"]", "[\"a\\tb\"]", "[\"a\\nb\"]", "[\" Work\\t\"]",
            "[\"\\u0007bell\"]", "[\"x\\u007f\"]", "[\"x\\u0085\"]", "[\"x\\u0000\"]", "[\"ok\",\"\\u001fx\"]"})
    void commaOrControlCharacterIsInvalidCharacters(String tagsArray) throws Exception {
        expectTagsError(tagsArray, "INVALID_CHARACTERS", "Tags can't contain commas or control characters");
        verifyNoInteractions(taskService);
    }

    @Test
    void tagOver30UnitsAfterNormalisingIsTooLong() throws Exception {
        expectTagsError(tagsJson("t".repeat(31)), "TOO_LONG", "Tags must be 30 characters or fewer");
        // 16 emoji = 32 UTF-16 code units.
        expectTagsError(tagsJson("\uD83D\uDE00".repeat(16)), "TOO_LONG", "Tags must be 30 characters or fewer");
        expectTagsError(tagsJson("a" + " ".repeat(14) + "b".repeat(29)), "TOO_LONG",
                "Tags must be 30 characters or fewer");
        verifyNoInteractions(taskService);
    }

    @Test
    void tagLengthCountsAfterNormalising() throws Exception {
        when(taskService.replace(eq(OWNER), eq(12L), any())).thenReturn(MILK);
        // 30 units after stripping; 15 emoji = 30 UTF-16 units; inner runs of (Unicode) spaces collapse to one.
        String collapsed = "a" + " \\u00a0\\u2003 ".repeat(10) + "b".repeat(28);

        mockMvc.perform(json(put("/api/tasks/12"), putWithTags("[\"  " + "t".repeat(30) + " \\u00a0\",\""
                        + "\uD83D\uDE00".repeat(15) + "\",\"" + collapsed + "\"]")))
                .andExpect(status().isOk());
    }

    @Test
    void moreThanTenDifferentTagsIsTooMany() throws Exception {
        String[] eleven = new String[11];
        for (int i = 0; i < 11; i++) {
            eleven[i] = "tag" + i;
        }
        expectTagsError(tagsJson(eleven), "TOO_MANY", "A task can have at most 10 tags");
        verifyNoInteractions(taskService);
    }

    @Test
    void duplicatesIgnoringCaseAccentsAndSpacesCountOnce() throws Exception {
        when(taskService.replace(eq(OWNER), eq(12L), any())).thenReturn(MILK);
        String[] tags = new String[14];
        for (int i = 0; i < 10; i++) {
            tags[i] = "tag" + i;
        }
        tags[10] = "TAG0";
        tags[11] = " t\u00c0g1 ";
        tags[12] = "ta\u0301g2";
        tags[13] = "tag3";

        mockMvc.perform(json(put("/api/tasks/12"), putWithTags(tagsJson(tags)))).andExpect(status().isOk());
    }

    @Test
    void tagErrorsFollowTheContractOrder() throws Exception {
        String tooLong = "t".repeat(31);
        String[] manyLong = new String[11];
        for (int i = 0; i < 11; i++) {
            manyLong[i] = tooLong + i;
        }
        expectTagsError("[\"a,b\",\"" + tooLong + "\",\" \"]", "REQUIRED", "Tags can't be blank");
        expectTagsError("[\"" + tooLong + "\",\"a,b\"]", "INVALID_CHARACTERS",
                "Tags can't contain commas or control characters");
        expectTagsError(tagsJson(manyLong), "TOO_LONG", "Tags must be 30 characters or fewer");
        verifyNoInteractions(taskService);
    }

    @Test
    void allFieldErrorsAreReportedTogetherSortedByField() throws Exception {
        String[] eleven = new String[11];
        for (int i = 0; i < 11; i++) {
            eleven[i] = "tag" + i;
        }
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\" \",\"description\":\"" + "d".repeat(2001)
                        + "\",\"status\":\"TODO\",\"done\":true,\"tags\":" + tagsJson(eleven) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[1].field").value("status"))
                .andExpect(jsonPath("$.errors[1].code").value("STATUS_CONFLICT"))
                .andExpect(jsonPath("$.errors[2].field").value("tags"))
                .andExpect(jsonPath("$.errors[2].code").value("TOO_MANY"))
                .andExpect(jsonPath("$.errors[3].field").value("title"));

        verifyNoInteractions(taskService);
    }

    @Test
    void tagLimitIsTheOnlyErrorEntry() throws Exception {
        when(taskService.replace(eq(OWNER), eq(12L), any())).thenThrow(new TagLimitExceededException());

        mockMvc.perform(json(put("/api/tasks/12"), putWithTags("[\"new\"]")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Bad Request","status":400,"detail":"Validation failed",
                         "instance":"/api/tasks/12","code":"VALIDATION_FAILED",
                         "errors":[{"field":"tags","code":"TAG_LIMIT","message":"You can have at most 200 tags"}]}""",
                        true));
    }

    @Test
    void patchPassesStatusAndOrDone() throws Exception {
        when(taskService.setStatus(anyLong(), anyLong(), any(), any())).thenReturn(MILK);

        mockMvc.perform(json(patch("/api/tasks/12"), "{\"status\":\"IN_PROGRESS\"}")).andExpect(status().isOk());
        verify(taskService).setStatus(OWNER, 12L, TaskStatus.IN_PROGRESS, null);

        mockMvc.perform(json(patch("/api/tasks/12"), "{\"status\":\"DONE\",\"done\":true}")).andExpect(status().isOk());
        verify(taskService).setStatus(OWNER, 12L, TaskStatus.DONE, true);

        mockMvc.perform(json(patch("/api/tasks/12"), "{\"status\":\"TODO\",\"done\":null}")).andExpect(status().isOk());
        verify(taskService).setStatus(OWNER, 12L, TaskStatus.TODO, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"TODO\",\"done\":true}", "{\"status\":\"IN_PROGRESS\",\"done\":true}",
            "{\"status\":\"DONE\",\"done\":false}"})
    void patchWithDisagreeingStatusAndDoneIsStatusConflict(String body) throws Exception {
        mockMvc.perform(json(patch("/api/tasks/12"), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("status"))
                .andExpect(jsonPath("$.errors[0].code").value("STATUS_CONFLICT"))
                .andExpect(jsonPath("$.errors[0].message").value("Status and done don't match"));

        verifyNoInteractions(taskService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"done\"}", "{\"status\":\"BLOCKED\"}", "{\"status\":2}", "{\"done\":\"maybe\"}"})
    void patchWithBadValueIsMalformed(String body) throws Exception {
        mockMvc.perform(json(patch("/api/tasks/12"), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        verifyNoInteractions(taskService);
    }
}
