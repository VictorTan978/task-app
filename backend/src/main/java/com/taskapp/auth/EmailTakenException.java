package com.taskapp.auth;

public class EmailTakenException extends RuntimeException {

    public EmailTakenException() {
        super("Email already registered");
    }
}
