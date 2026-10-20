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

    // ---- reminder email (T4a, AC15) ----

    @org.junit.jupiter.api.Test
    void reminderEmailHasTheTitleDueTimeListLinkAndHowToTurnItOff() {
        EmailContent mail = EmailTemplates.reminder("Ana", "Dentist", java.time.LocalDate.of(2026, 10, 21),
                java.time.LocalTime.of(9, 30), "My tasks", "https://app.example.com/tasks", false);

        org.assertj.core.api.Assertions.assertThat(mail.subject()).isEqualTo("Reminder: Dentist");
        org.assertj.core.api.Assertions.assertThat(mail.text()).contains("Hi Ana,",
                "Your task \"Dentist\" is due on Wed, 21 Oct 2026 at 09:30.", "List: My tasks",
                "https://app.example.com/tasks", "switch off \"Reminder emails\"").doesNotContain("late");
        org.assertj.core.api.Assertions.assertThat(mail.html()).contains("Dentist", "Wed, 21 Oct 2026 at 09:30",
                "href=\"https://app.example.com/tasks\"", "Reminder emails");
    }

    @org.junit.jupiter.api.Test
    void aDateOnlyReminderShowsNoTimeAndALateOneSaysSo() {
        EmailContent mail = EmailTemplates.reminder("Ana", "Pay rent", java.time.LocalDate.of(2026, 10, 21), null,
                "Home", "https://app.example.com/lists/8", true);

        org.assertj.core.api.Assertions.assertThat(mail.text()).contains("is due on Wed, 21 Oct 2026. This reminder is late.")
                .doesNotContain(" at ");
        org.assertj.core.api.Assertions.assertThat(mail.html()).contains("This reminder is late.");
    }

    @org.junit.jupiter.api.Test
    void userInputIsEscapedInHtmlAndFlattenedInTextAndSubject() {
        EmailContent mail = EmailTemplates.reminder("<i>Ana</i>", "Buy <b>milk</b>\r\nBcc: evil@example.com & more",
                java.time.LocalDate.of(2026, 10, 21), null, "Home\n<script>x</script>", "https://app.example.com/tasks",
                false);

        org.assertj.core.api.Assertions.assertThat(mail.subject()).doesNotContain("\n", "\r")
                .startsWith("Reminder: Buy <b>milk</b> Bcc: evil@example.com & more");
        org.assertj.core.api.Assertions.assertThat(mail.html()).doesNotContain("<b>", "<script>", "<i>")
                .contains("Buy &lt;b&gt;milk&lt;/b&gt; Bcc: evil@example.com &amp; more", "&lt;script&gt;");
        org.assertj.core.api.Assertions.assertThat(mail.text().lines().filter(l -> l.startsWith("Bcc:"))).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void aLongTitleIsShortenedInTheSubjectOnly() {
        String title = "x".repeat(200);
        EmailContent mail = EmailTemplates.reminder("Ana", title, java.time.LocalDate.of(2026, 10, 21), null, "Home",
                "https://app.example.com/tasks", false);

        org.assertj.core.api.Assertions.assertThat(mail.subject()).hasSize("Reminder: ".length() + 80).endsWith("…");
        org.assertj.core.api.Assertions.assertThat(mail.text()).contains(title);
    }

    @org.junit.jupiter.api.Test
    void theReminderEmailRedactsItsBodyInToString() {
        EmailContent mail = EmailTemplates.reminder("Ana", "Secret title", java.time.LocalDate.of(2026, 10, 21), null,
                "Home", "https://app.example.com/tasks", false);
        org.assertj.core.api.Assertions.assertThat(mail.toString()).contains("text=****", "html=****");
    }

    @org.junit.jupiter.api.Test
    void theSubjectIsCutOnCodePointsSoAnEmojiIsNeverSplit() {
        String title = "😀".repeat(100);
        EmailContent mail = EmailTemplates.reminder("Ana", title, java.time.LocalDate.of(2026, 10, 21), null, "Home",
                "https://app.example.com/tasks", false);

        String subject = mail.subject();
        org.assertj.core.api.Assertions.assertThat(subject).startsWith("Reminder: " + "😀".repeat(79)).endsWith("…");
        org.assertj.core.api.Assertions.assertThat(subject.codePointCount(0, subject.length())).isEqualTo(10 + 80);
        org.assertj.core.api.Assertions.assertThat(subject.chars().filter(c -> Character.isSurrogate((char) c)).count() % 2).isZero();
    }
}
