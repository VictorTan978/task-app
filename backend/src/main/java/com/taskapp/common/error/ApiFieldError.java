package com.taskapp.common.error;

/** One entry of the ProblemDetail {@code errors} array. */
public record ApiFieldError(String field, String code, String message) {
}
