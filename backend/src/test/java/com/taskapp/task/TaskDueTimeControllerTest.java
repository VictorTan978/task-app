package com.taskapp.task;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.taskapp.reminder.ReminderOffset;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.UnchangedSessionUserLookup;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.TaskResponse;
import com.taskapp.task.dto.TaskResult;

/** Contract 0.14.0 at the HTTP layer: {@code dueTime} and {@code reminder} of PUT, and the new Task fields. */
@WebMvcTest(TaskController.class)
@Import({SecurityConfig.class, UnchangedSessionUserLookup.class})
class TaskDueTimeControllerTest {

    private static final long OWNER = 7L;
    private static final Instant CREATED = Instant.parse("2026-10-08T09:15:02.123456Z");

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

    private static TaskResponse timed(LocalTime time, ReminderOffset reminder, boolean passed) {
        return new TaskResponse(12L, 3L, new TaskResponse.AddedBy("Ana"), "Dentist", null, Priority.NONE,
                TaskStatus.TODO, List.of(), false, LocalDate.parse("2026-10-21"), time, reminder, passed, CREATED,
                CREATED);
    }

    private ReplaceTaskRequest sent(String body) throws Exception {
        when(taskService.replace(eq(OWNER), eq(12L), any())).thenReturn(TaskResult.of(timed(null, null, false)));
        mockMvc.perform(json(put("/api/tasks/12"), body)).andExpect(status().isOk());
        ArgumentCaptor<ReplaceTaskRequest> captor = ArgumentCaptor.forClass(ReplaceTaskRequest.class);
        verify(taskService).replace(eq(OWNER), eq(12L), captor.capture());
        return captor.getValue();
    }

    @Test
    void theResponseCarriesTheTimeAndMyReminderInContractOrder() throws Exception {
        when(taskService.replace(eq(OWNER), eq(12L), any())).thenReturn(TaskResult.of(timed(LocalTime.of(9, 5),
                ReminderOffset.ONE_HOUR, true)));

        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"Dentist\",\"dueDate\":\"2026-10-21\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueTime").value("09:05"))
                .andExpect(jsonPath("$.myReminder").value("ONE_HOUR"))
                .andExpect(jsonPath("$.myReminderPassed").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"dueDate\":\"2026-10-21\",\"dueTime\":\"09:05\",\"repeat\":null,\"myReminder\":\"ONE_HOUR\","
                                + "\"myReminderPassed\":true,\"createdAt\"")));
    }

    @Test
    void aTaskWithoutTimeOrReminderShowsNulls() throws Exception {
        when(taskService.replace(eq(OWNER), eq(12L), any())).thenReturn(TaskResult.of(timed(null, null, false)));
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"Dentist\"}"))
                .andExpect(jsonPath("$.dueTime").value((Object) null))
                .andExpect(jsonPath("$.myReminder").value((Object) null))
                .andExpect(jsonPath("$.myReminderPassed").value(false));
    }

    @Test
    void dueTimeHasThreeStatesMissingNullAndAValue() throws Exception {
        assertThat(sent("{\"title\":\"T\",\"dueDate\":\"2026-10-21\"}").dueTime()).isNull();
    }

    @Test
    void anExplicitNullClearsTheDueTime() throws Exception {
        var request = sent("{\"title\":\"T\",\"dueDate\":\"2026-10-21\",\"dueTime\":null}");
        assertThat(request.dueTime()).isNotNull();
        assertThat(request.dueTime().value()).isNull();
        assertThat(request.setsDueTime()).isFalse();
    }

    @Test
    void aValueIsStrictHhMm() throws Exception {
        var request = sent("{\"title\":\"T\",\"dueDate\":\"2026-10-21\",\"dueTime\":\"23:59\"}");
        assertThat(request.dueTime().value()).isEqualTo(LocalTime.of(23, 59));
        assertThat(request.setsDueTime()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"9:30\"", "\"09:30:15\"", "\"24:00\"", "\"12:60\"", "\"0930\"", "\"\"", "\" 09:30\"",
            "\"09:30\\n\"", "930", "9.5", "true", "[9,30]", "{}", "\"9:3\""})
    void anythingButHhMmIsMalformed(String value) throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"T\",\"dueDate\":\"2026-10-21\",\"dueTime\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verifyNoInteractions(taskService);
    }

    @Test
    void aTimeWithoutADateIsRequiresDueDate() throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"T\",\"dueTime\":\"09:30\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("dueTime"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRES_DUE_DATE"))
                .andExpect(jsonPath("$.errors[0].message").value("A due time needs a due date"));
        verifyNoInteractions(taskService);
    }

    @Test
    void aReminderWithoutADateIsRequiresDueDateButNoneAndNullNeedNoDate() throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"T\",\"reminder\":\"ONE_DAY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reminder"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRES_DUE_DATE"))
                .andExpect(jsonPath("$.errors[0].message").value("A reminder needs a due date"));
        verifyNoInteractions(taskService);
        assertThat(sent("{\"title\":\"T\",\"reminder\":\"NONE\",\"dueTime\":null}").reminder()).isEqualTo("NONE");
    }

    @Test
    void bothAreReportedTogetherSortedByField() throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"\",\"dueTime\":\"09:30\",\"reminder\":\"AT_DUE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("dueTime"))
                .andExpect(jsonPath("$.errors[1].field").value("reminder"))
                .andExpect(jsonPath("$.errors[2].field").value("title"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"one_hour\"", "\"WEEKLY\"", "\"\"", "\" ONE_HOUR\"", "\"none\""})
    void anUnknownReminderValueIsInvalidReminder(String value) throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"T\",\"dueDate\":\"2026-10-21\",\"reminder\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("reminder"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_REMINDER"));
        verifyNoInteractions(taskService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "[\"ONE_HOUR\"]", "{}"})
    void aReminderThatIsNotAStringIsMalformed(String value) throws Exception {
        mockMvc.perform(json(put("/api/tasks/12"), "{\"title\":\"T\",\"dueDate\":\"2026-10-21\",\"reminder\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AT_DUE", "TEN_MINUTES", "ONE_HOUR", "ONE_DAY", "ON_THE_DAY", "NONE"})
    void everyOffsetAndNoneIsPassedToTheService(String value) throws Exception {
        assertThat(sent("{\"title\":\"T\",\"dueDate\":\"2026-10-21\",\"reminder\":\"" + value + "\"}").reminder())
                .isEqualTo(value);
    }

    @Test
    void aRequestWithoutTheNewFieldsMeansUnchanged() throws Exception {
        var request = sent("{\"title\":\"T\",\"dueDate\":\"2026-10-21\"}");
        assertThat(request.dueTime()).isNull();
        assertThat(request.reminder()).isNull();
        when(taskService.replace(anyLong(), anyLong(), any())).thenReturn(TaskResult.of(timed(null, null, false)));
    }
}
