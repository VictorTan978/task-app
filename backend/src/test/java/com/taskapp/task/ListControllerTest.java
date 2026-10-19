package com.taskapp.task;

import static com.taskapp.security.TestSessions.signedInSession;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.task.dto.CreateListRequest;
import com.taskapp.task.dto.RenameListRequest;
import com.taskapp.task.dto.TaskListDetailsResponse;
import com.taskapp.task.dto.TaskListResponse;

/** {@code /api/lists} (contract 0.12.0): shapes, validation, status codes and the check order. */
@WebMvcTest(ListController.class)
@Import({SecurityConfig.class, UnchangedSessionUserLookup.class})
class ListControllerTest {

    private static final long OWNER = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ListService listService;

    private static RequestPostProcessor signedIn() {
        var principal = new AuthenticatedUser(OWNER, "alice@example.com", "Alice");
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.with(signedIn()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void listReturnsTheListsInContractShape() throws Exception {
        when(listService.list(OWNER)).thenReturn(List.of(new TaskListResponse(3, "My tasks", true),
                new TaskListResponse(8, "Home", false)));

        mockMvc.perform(get("/api/lists").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        [{"id":3,"name":"My tasks","personal":true},{"id":8,"name":"Home","personal":false}]""",
                        true));
    }

    @Test
    void getReturnsTheTaskCount() throws Exception {
        when(listService.get(OWNER, 8L)).thenReturn(new TaskListDetailsResponse(8, "Home", false, 12));

        mockMvc.perform(get("/api/lists/8").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":8,\"name\":\"Home\",\"personal\":false,\"taskCount\":12}", true));
    }

    @Test
    void notMineIs404ListNotFoundOnEveryPath() throws Exception {
        when(listService.get(OWNER, 9L)).thenThrow(new ListNotFoundException());
        when(listService.rename(eq(OWNER), eq(9L), any())).thenThrow(new ListNotFoundException());
        doThrow(new ListNotFoundException()).when(listService).delete(OWNER, 9L);

        for (MockHttpServletRequestBuilder request : List.of(get("/api/lists/9").with(signedIn()),
                json(patch("/api/lists/9"), "{\"name\":\"x\"}"),
                delete("/api/lists/9").with(signedIn()).with(csrf()))) {
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(content().json("""
                            {"type":"about:blank","title":"Not Found","status":404,"detail":"List not found",
                             "instance":"/api/lists/9","code":"LIST_NOT_FOUND"}""", true));
        }
    }

    @Test
    void nonIntegerIdIsMalformed() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(get("/api/lists/abc").with(signedIn()),
                json(patch("/api/lists/abc"), "{\"name\":\"\"}"),
                delete("/api/lists/abc").with(signedIn()).with(csrf()))) {
            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.detail").value("Failed to convert 'id' with value 'abc'"));
        }
        verifyNoInteractions(listService);
    }

    @Test
    void createReturns201WithLocationAndAStrippedName() throws Exception {
        when(listService.create(eq(OWNER), any())).thenReturn(new TaskListResponse(8, "Home", false));

        mockMvc.perform(json(post("/api/lists"), "{\"name\":\"  Home \",\"personal\":true,\"id\":1,\"ownerId\":2}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/lists/8"))
                .andExpect(content().json("{\"id\":8,\"name\":\"Home\",\"personal\":false}", true));
        verify(listService).create(OWNER, new CreateListRequest("Home"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\" \\t\\n\"}"})
    void nameIsRequired(String body) throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(post("/api/lists"), patch("/api/lists/8"))) {
            mockMvc.perform(json(request, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("name"))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                    .andExpect(jsonPath("$.errors[0].message").value("Name is required"));
        }
        verifyNoInteractions(listService);
    }

    @Test
    void nameIsAtMost100UnitsAfterStripping() throws Exception {
        when(listService.create(eq(OWNER), any())).thenReturn(new TaskListResponse(8, "x", false));
        when(listService.rename(eq(OWNER), eq(8L), any())).thenReturn(new TaskListResponse(8, "x", false));
        String hundred = "é".repeat(98) + "😀";

        mockMvc.perform(json(post("/api/lists"), "{\"name\":\"  " + hundred + " \"}")).andExpect(status().isCreated());
        verify(listService).create(OWNER, new CreateListRequest(hundred));
        mockMvc.perform(json(patch("/api/lists/8"), "{\"name\":\"" + hundred + "\"}")).andExpect(status().isOk());
        verify(listService).rename(OWNER, 8L, new RenameListRequest(hundred));

        for (MockHttpServletRequestBuilder request : List.of(post("/api/lists"), patch("/api/lists/8"))) {
            mockMvc.perform(json(request, "{\"name\":\"" + hundred + "x\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("name"))
                    .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"))
                    .andExpect(jsonPath("$.errors[0].message").value("Name must be 100 characters or fewer"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":1}", "{\"name\":true}", "{\"name\":[\"a\"]}", "{\"name\":{}}", "not json", ""})
    void nonStringNameOrBadJsonIsMalformed(String body) throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(post("/api/lists"), patch("/api/lists/8"))) {
            mockMvc.perform(json(request, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.detail").value("Malformed request body"))
                    .andExpect(jsonPath("$.errors").doesNotExist());
        }
        verifyNoInteractions(listService);
    }

    @Test
    void the51stListIs409ListLimit() throws Exception {
        when(listService.create(eq(OWNER), any())).thenThrow(new ListLimitException());

        mockMvc.perform(json(post("/api/lists"), "{\"name\":\"One more\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Conflict","status":409,
                         "detail":"You can have at most 50 lists","instance":"/api/lists","code":"LIST_LIMIT"}""",
                        true));
    }

    @Test
    void renameReturnsTheList() throws Exception {
        when(listService.rename(OWNER, 3L, new RenameListRequest("Inbox")))
                .thenReturn(new TaskListResponse(3, "Inbox", true));

        mockMvc.perform(json(patch("/api/lists/3"), "{\"name\":\" Inbox \",\"personal\":false}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":3,\"name\":\"Inbox\",\"personal\":true}", true));
    }

    @Test
    void deleteIs204AndMyTasksIs409PersonalList() throws Exception {
        mockMvc.perform(delete("/api/lists/8").with(signedIn()).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(listService).delete(OWNER, 8L);

        doThrow(new PersonalListException()).when(listService).delete(OWNER, 3L);
        mockMvc.perform(delete("/api/lists/3").with(signedIn()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Conflict","status":409,
                         "detail":"Your personal list can't be deleted","instance":"/api/lists/3",
                         "code":"PERSONAL_LIST"}""", true));
    }

    @Test
    void requiresSignInAndCsrf() throws Exception {
        mockMvc.perform(get("/api/lists")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/lists").with(signedIn()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(delete("/api/lists/8").with(signedIn())).andExpect(status().isForbidden());
        verifyNoInteractions(listService);
    }

    @Test
    void anyIdIsPassedThroughForTheServiceToCheck() throws Exception {
        when(listService.get(eq(OWNER), anyLong())).thenThrow(new ListNotFoundException());

        mockMvc.perform(get("/api/lists/0").with(signedIn())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/lists/-1").with(signedIn())).andExpect(status().isNotFound());
    }
}
