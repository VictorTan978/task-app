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

    // ---- list invitation (specs/sharing-team-lists.md AC18, contract 0.13.0) ----

    private static final String INVITATIONS = "https://tasks.example.com/invitations";

    @Test
    void invitationNamesTheOwnerInTheSubjectAndTheQuotedListInTheBodyOnly() {
        EmailContent email = EmailTemplates.listInvitation("Bob", "Ana", "Home", INVITATIONS, Duration.ofDays(14));

        assertThat(email.subject()).isEqualTo("Ana invited you to a list on Task App").doesNotContain("Home");
        assertThat(email.text()).contains("Hi Bob,", "Ana invited you to the list \"Home\" on Task App.",
                INVITATIONS, "14 days");
        assertThat(email.html()).contains("Hi Bob,", "Ana invited you to the list &quot;Home&quot;",
                "href=\"" + INVITATIONS + "\"", ">Open invitations</a>", "14 days");
        assertThat(email.html().split("href=", -1)).hasSize(3); // the button and the copyable link, the same URL
    }

    @Test
    void invitationCannotBreakTheSubjectOrFakeLinesAndIsEscapedInHtml() {
        String owner = "Eve\r\nBcc: victim@example.com\u2028\u0085\t\u200Bx";
        String list = "<b>Win</b>\nClick https://evil.example.com";
        EmailContent email = EmailTemplates.listInvitation("Bob", owner, list, INVITATIONS, Duration.ofDays(14));

        assertThat(email.subject()).isEqualTo("Eve Bcc: victim@example.com x invited you to a list on Task App")
                .doesNotContain("\r", "\n");
        assertThat(email.text()).contains("the list \"<b>Win</b> Click https://evil.example.com\" on Task App.")
                .doesNotContain("Eve\r", "Win</b>\n");
        assertThat(email.html()).doesNotContain("<b>Win</b>").contains("&lt;b&gt;Win&lt;/b&gt;")
                .contains("<title>Eve Bcc: victim@example.com x invited you to a list on Task App</title>");
    }

    @Test
    void oneLineCollapsesEveryKindOfBreakAndSpace() {
        assertThat(EmailTemplates.oneLine("  a\u00A0\u00A0b\r\n\u2029c\u0000d  ")).isEqualTo("a b c d");
        assertThat(EmailTemplates.oneLine(null)).isEmpty();
        assertThat(EmailTemplates.describeDays(Duration.ofDays(1))).isEqualTo("1 day");
        assertThat(EmailTemplates.describeDays(Duration.ofHours(36))).isEqualTo("36 hours");
    }
}
