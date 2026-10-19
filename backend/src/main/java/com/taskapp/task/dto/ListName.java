package com.taskapp.task.dto;

/** List name rules (contract 0.12.0 {@code CreateListRequest.name}). */
final class ListName {

    static final int MAX_LENGTH = 100;
    static final String REQUIRED_MESSAGE = "Name is required";
    static final String TOO_LONG_MESSAGE = "Name must be 100 characters or fewer";

    private ListName() {
    }
}
