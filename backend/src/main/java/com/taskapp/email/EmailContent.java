package com.taskapp.email;

/**
 * A ready-to-send email. The bodies contain a secret link: never log this object or its parts
 * ({@link #toString()} is redacted).
 */
public record EmailContent(String subject, String text, String html) {

    @Override
    public String toString() {
        return "EmailContent[subject=" + subject + ", text=****, html=****]";
    }
}
