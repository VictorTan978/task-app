package com.taskapp.notification;

/** The notification does not exist or is another user's (404 NOTIFICATION_NOT_FOUND, identical body). */
public class NotificationNotFoundException extends RuntimeException {

    public static final String MESSAGE = "Notification not found";

    public NotificationNotFoundException() {
        super(MESSAGE, null, false, false);
    }
}
