package com.taskapp.auth;

import static com.taskapp.security.TestSessions.signedInSession;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.Role;
import com.taskapp.security.SecurityConfig;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.UnchangedSessionUserLookup;

/** Contract 0.14.0: {@code PUT /api/account/time-zone} and {@code /reminder-emails} at the HTTP layer. */
@WebMvcTest(controllers = AccountController.class)
@Import({SecurityConfig.class, SessionAuthenticator.class, UnchangedSessionUserLookup.class})
class AccountReminderSettingsControllerTest {

    private static final AuthenticatedUser PRINCIPAL = new AuthenticatedUser(1L, "bob@example.com", "Bob");
    private static final String ZONE = "/api/account/time-zone";
    private static final String EMAILS = "/api/account/reminder-emails";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    private static RequestPostProcessor signedIn() {
        return signedInSession(UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL, null, List.of()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.with(signedIn()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static UserResponse user(String zone, boolean emails) {
        return new UserResponse(1L, "bob@example.com", "Bob", true, true, List.of(), Role.USER, zone, emails);
    }

    @Test
    void setTimeZoneReturnsTheUserAndPassesIfUnset() throws Exception {
        when(accountService.setTimeZone(1L, "Europe/Bucharest", true)).thenReturn(user("Europe/Bucharest", true));

        mockMvc.perform(json(put(ZONE), "{\"timeZone\":\"Europe/Bucharest\",\"ifUnset\":true}"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":1,"email":"bob@example.com","displayName":"Bob","emailVerified":true,
                         "hasPassword":true,"connections":[],"role":"USER","timeZone":"Europe/Bucharest",
                         "reminderEmails":true}""", true));
        verify(accountService).setTimeZone(1L, "Europe/Bucharest", true);
    }

    @Test
    void ifUnsetDefaultsToFalse() throws Exception {
        when(accountService.setTimeZone(1L, "UTC", false)).thenReturn(user("UTC", true));
        mockMvc.perform(json(put(ZONE), "{\"timeZone\":\"UTC\"}")).andExpect(status().isOk());
        mockMvc.perform(json(put(ZONE), "{\"timeZone\":\"UTC\",\"ifUnset\":null}")).andExpect(status().isOk());
        mockMvc.perform(json(put(ZONE), "{\"timeZone\":\"UTC\",\"ifUnset\":false}")).andExpect(status().isOk());
        verify(accountService, org.mockito.Mockito.times(3)).setTimeZone(1L, "UTC", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+02:00", "UTC+2", "EST", "europe/bucharest", "Mars/Base", " UTC", "Z", "GMT+2"})
    void aZoneThatIsNotARegionIdIsInvalidTimeZone(String zone) throws Exception {
        mockMvc.perform(json(put(ZONE), "{\"timeZone\":\"" + zone + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("timeZone"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_TIME_ZONE"))
                .andExpect(jsonPath("$.errors[0].message").value("Unknown time zone"));
        verifyNoInteractions(accountService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"timeZone\":null}", "{\"timeZone\":\"\"}", "{\"timeZone\":\"   \"}"})
    void aMissingOrBlankZoneIsRequired(String body) throws Exception {
        mockMvc.perform(json(put(ZONE), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("timeZone"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        verifyNoInteractions(accountService);
    }

    @Test
    void aMalformedBodyOrWrongTypeIs400Malformed() throws Exception {
        mockMvc.perform(json(put(ZONE), "{\"timeZone\":")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(json(put(ZONE), "{\"timeZone\":\"UTC\",\"ifUnset\":\"yes\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(accountService);
    }

    @Test
    void setReminderEmailsReturnsTheUser() throws Exception {
        when(accountService.setReminderEmails(1L, false)).thenReturn(user(null, false));

        mockMvc.perform(json(put(EMAILS), "{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reminderEmails").value(false))
                .andExpect(jsonPath("$.timeZone").value((Object) null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"enabled\":null}"})
    void enabledIsRequired(String body) throws Exception {
        mockMvc.perform(json(put(EMAILS), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("enabled"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        verifyNoInteractions(accountService);
    }

    @Test
    void enabledMustBeABoolean() throws Exception {
        mockMvc.perform(json(put(EMAILS), "{\"enabled\":\"yes\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(accountService);
    }

    @Test
    void bothNeedASessionAndCsrf() throws Exception {
        mockMvc.perform(put(ZONE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"timeZone\":\"UTC\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(EMAILS).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(ZONE).with(signedIn()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"timeZone\":\"UTC\"}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(put(EMAILS).with(signedIn()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(accountService);
        when(accountService.setTimeZone(anyLong(), anyString(), anyBoolean())).thenReturn(user("UTC", true));
    }
}
