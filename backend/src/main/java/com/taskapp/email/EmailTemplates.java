package com.taskapp.email;

import java.time.Duration;

import org.springframework.web.util.HtmlUtils;

/**
 * The two emails of specs/email-auth.md (AC1, AC8, AC14), as plain text plus a simple HTML version. The display
 * name is user input: it is HTML-escaped in the HTML part. Links are built from {@code APP_BASE_URL} and a
 * base64url token, so they need no URL encoding.
 */
public final class EmailTemplates {

    private static final String VERIFY_TEXT = """
            Hi %s,

            Please confirm your email address for Task App by opening this link:

            %s

            The link works for %s and can be used once. If you didn't create a Task App account, you can ignore \
            this email.
            """;

    private static final String RESET_TEXT = """
            Hi %s,

            Someone asked to reset the password of your Task App account. To choose a new password, open this link:

            %s

            The link works for %s and can be used once. If you didn't ask for this, you can ignore this email: \
            your password stays the same.
            """;

    private static final String HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="UTF-8"><meta name="referrer" content="no-referrer"><title>%1$s</title></head>
            <body style="margin:0;padding:24px;background:#f5f5f5;font-family:Arial,Helvetica,sans-serif;color:#222;">
              <div style="max-width:480px;margin:0 auto;background:#fff;border-radius:8px;padding:24px;">
                <p style="margin:0 0 16px;">Hi %2$s,</p>
                <p style="margin:0 0 24px;">%3$s</p>
                <p style="margin:0 0 24px;">
                  <a href="%4$s" style="display:inline-block;padding:12px 20px;background:#3f51b5;color:#fff;\
            text-decoration:none;border-radius:4px;">%5$s</a>
                </p>
                <p style="margin:0 0 8px;font-size:13px;color:#555;">Or copy this link into your browser:</p>
                <p style="margin:0 0 24px;font-size:13px;word-break:break-all;"><a href="%4$s">%4$s</a></p>
                <p style="margin:0;font-size:13px;color:#555;">%6$s</p>
              </div>
            </body>
            </html>
            """;

    private EmailTemplates() {
    }

    /** "Confirm your email for Task App" with a "Confirm email" link. */
    public static EmailContent verifyEmail(String displayName, String link, Duration validFor) {
        String subject = "Confirm your email for Task App";
        String validity = describe(validFor);
        return new EmailContent(subject,
                VERIFY_TEXT.formatted(displayName, link, validity),
                HTML.formatted(subject, HtmlUtils.htmlEscape(displayName),
                        "Please confirm your email address for Task App.",
                        HtmlUtils.htmlEscape(link), "Confirm email",
                        "The link works for " + validity + " and can be used once. If you didn't create a Task App"
                                + " account, you can ignore this email."));
    }

    /** "Reset your Task App password" with a "Reset password" link. */
    public static EmailContent resetPassword(String displayName, String link, Duration validFor) {
        String subject = "Reset your Task App password";
        String validity = describe(validFor);
        return new EmailContent(subject,
                RESET_TEXT.formatted(displayName, link, validity),
                HTML.formatted(subject, HtmlUtils.htmlEscape(displayName),
                        "Someone asked to reset the password of your Task App account. To choose a new password,"
                                + " use the button below.",
                        HtmlUtils.htmlEscape(link), "Reset password",
                        "The link works for " + validity + " and can be used once. If you didn't ask for this, you"
                                + " can ignore this email: your password stays the same."));
    }

    /** "24 hours", "1 hour", "30 minutes", "45 seconds". */
    static String describe(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds >= 3600 && seconds % 3600 == 0) {
            return plural(seconds / 3600, "hour");
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            return plural(seconds / 60, "minute");
        }
        return plural(seconds, "second");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }
}
