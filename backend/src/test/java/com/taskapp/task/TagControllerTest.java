package com.taskapp.task;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.taskapp.task.dto.TagResponse;

@WebMvcTest(TagController.class)
@Import({SecurityConfig.class, UnchangedSessionUserLookup.class})
class TagControllerTest {

    private static final long OWNER = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TagService tagService;

    private static RequestPostProcessor signedIn() {
        var principal = new AuthenticatedUser(OWNER, "alice@example.com", "Alice");
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    @Test
    void listReturnsTagObjectsForTheSessionUser() throws Exception {
        when(tagService.list(OWNER, null)).thenReturn(List.of(new TagResponse("Errands"), new TagResponse("Wörk")));

        mockMvc.perform(get("/api/tags?ownerId=1").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[{\"name\":\"Errands\"},{\"name\":\"Wörk\"}]", true));
    }

    @Test
    void emptyListIsAnEmptyArray() throws Exception {
        when(tagService.list(OWNER, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/tags").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", true));
    }

    @Test
    void passesTheFirstRawListValue() throws Exception {
        when(tagService.list(OWNER, "8")).thenReturn(List.of(new TagResponse("Home")));

        mockMvc.perform(get("/api/tags").param("list", "8", "9").with(signedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("[{\"name\":\"Home\"}]", true));
    }

    @Test
    void listNotMineIs404ListNotFound() throws Exception {
        when(tagService.list(OWNER, "abc")).thenThrow(new ListNotFoundException());

        mockMvc.perform(get("/api/tags").param("list", "abc").with(signedIn()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LIST_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("List not found"));
    }

    @Test
    void requiresSignIn() throws Exception {
        mockMvc.perform(get("/api/tags"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(tagService);
    }
}
