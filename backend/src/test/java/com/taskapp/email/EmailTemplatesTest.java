package com.taskapp.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** specs/email-auth.md AC1, AC8, AC14: subjects, links, validity, and the display name escaped in HTML. */
class EmailTemplatesTest {

    private static final String LINK = "https://tasks.example.com/verify-email?token=abc_DEF-123";

    @Test
    void confirmationEmailHasSubjectLinkAndValidityInBothParts() {
        EmailContent email = EmailTemplates.verifyEmail("Bob", LINK, Duration.ofHours(24));

        assertThat(email.subject()).isEqualTo("Confirm your email for Task App");
        assertThat(email.text()).contains("Hi Bob,", LINK, "24 hours");
        assertThat(email.html()).contains("Hi Bob,", "href=\"" + LINK + "\"", ">Confirm email</a>", "24 hours");
    }

    @Test
    void resetEmailHasSubjectLinkAndValidity() {
        String link = "https://tasks.example.com/reset-password?token=abc";
        EmailContent email = EmailTemplates.resetPassword("Bob", link, Duration.ofHours(1));

        assertThat(email.subject()).isEqualTo("Reset your Task App password");
        assertThat(email.text()).contains(link, "1 hour ").doesNotContain("1 hours");
        assertThat(email.html()).contains("href=\"" + link + "\"", ">Reset password</a>", "1 hour ");
    }

    @Test
    void displayNameIsHtmlEscapedInTheHtmlPartOnly() {
        String name = "<script>alert(1)</script> & \"Bob\"";
        EmailContent email = EmailTemplates.verifyEmail(name, LINK, Duration.ofHours(24));

        assertThat(email.html()).doesNotContain("<script>")
                .contains("Hi &lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;Bob&quot;,");
        assertThat(email.text()).contains("Hi " + name + ",");
    }

    @Test
    void toStringNeverShowsTheBodies() {
        assertThat(EmailTemplates.verifyEmail("Bob", LINK, Duration.ofHours(24)).toString())
                .doesNotContain(LINK, "token");
    }

    @Test
    void durationsAreWrittenInWords() {
        assertThat(EmailTemplates.describe(Duration.ofHours(24))).isEqualTo("24 hours");
        assertThat(EmailTemplates.describe(Duration.ofHours(1))).isEqualTo("1 hour");
        assertThat(EmailTemplates.describe(Duration.ofMinutes(30))).isEqualTo("30 minutes");
        assertThat(EmailTemplates.describe(Duration.ofSeconds(90))).isEqualTo("90 seconds");
    }

    @Test
    void baseUrlTrailingSlashIsIgnored() {
        EmailProperties properties = new EmailProperties("https://tasks.example.com/ ", "a@b.c", Duration.ofHours(24),
                Duration.ofHours(1));
        assertThat(properties.baseUrl()).isEqualTo("https://tasks.example.com");
    }
}
