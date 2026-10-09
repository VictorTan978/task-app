package com.taskapp.common.error;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.taskapp.auth.InvalidCredentialsException;
import com.taskapp.auth.RegistrationConflictException;
import com.taskapp.auth.UnauthenticatedException;
import com.taskapp.task.TaskNotFoundException;

/**
 * Turns every error raised in the MVC layer into an {@code application/problem+json} body with a {@code code}.
 * Errors raised by the security filter chain (401/403) are written by the handlers in {@code com.taskapp.security}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String EMAIL_TAKEN_MESSAGE = "An account with this email already exists";
    private static final String DISPLAY_NAME_TAKEN_MESSAGE = "This display name is already taken";

    /** Lower value wins when one field has several violations. */
    private static final Map<String, Integer> FIELD_CODE_PRIORITY = Map.of(
            ErrorCode.REQUIRED, 0,
            ErrorCode.INVALID_EMAIL, 1,
            ErrorCode.PASSWORD_LENGTH, 1,
            ErrorCode.TOO_LONG, 2);

    /**
     * One 409 for register: top-level code is EMAIL_TAKEN whenever the email is taken (backward compatible),
     * otherwise DISPLAY_NAME_TAKEN; {@code errors} has one entry per taken field.
     */
    @ExceptionHandler(RegistrationConflictException.class)
    ResponseEntity<ProblemDetail> handleRegistrationConflict(RegistrationConflictException ex) {
        List<ApiFieldError> errors = new ArrayList<>();
        if (ex.emailTaken()) {
            errors.add(new ApiFieldError("email", ErrorCode.EMAIL_TAKEN, EMAIL_TAKEN_MESSAGE));
        }
        if (ex.displayNameTaken()) {
            errors.add(new ApiFieldError("displayName", ErrorCode.DISPLAY_NAME_TAKEN, DISPLAY_NAME_TAKEN_MESSAGE));
        }
        ApiFieldError top = errors.get(0);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Problems.of(HttpStatus.CONFLICT, top.code(), top.message(), errors));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Problems.of(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Invalid email or password"));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    ResponseEntity<ProblemDetail> handleUnauthenticated(UnauthenticatedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Problems.of(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Authentication required"));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    ResponseEntity<ProblemDetail> handleTaskNotFound(TaskNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                Problems.of(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Task not found"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Problems.of(
                HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "An unexpected error occurred"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, ApiFieldError> byField = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            ApiFieldError candidate = new ApiFieldError(fe.getField(), fieldCode(fe), fe.getDefaultMessage());
            byField.merge(fe.getField(), candidate, (current, next) ->
                    priority(next.code()) < priority(current.code()) ? next : current);
        }
        List<ApiFieldError> errors = byField.values().stream()
                .sorted(Comparator.comparing(ApiFieldError::field))
                .toList();
        return ResponseEntity.badRequest().body(
                Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Validation failed", errors));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(
                Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "Malformed request body"));
    }

    /** Path/query parameter of the wrong type, e.g. {@code /api/tasks/abc}. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String detail = "Failed to convert '" + ex.getPropertyName() + "' with value '" + ex.getValue() + "'";
        return ResponseEntity.badRequest().body(
                Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, detail));
    }

    /** Adds a {@code code} to the ProblemDetails Spring MVC builds for its own exceptions (404, 405, 415, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            problem.setProperty("code", codeForStatus(statusCode));
        }
        return response;
    }

    private static String codeForStatus(HttpStatusCode status) {
        if (status.value() == 404) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.value() == 401) {
            return ErrorCode.UNAUTHENTICATED;
        }
        if (status.value() == 403) {
            return ErrorCode.FORBIDDEN;
        }
        return status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.MALFORMED_REQUEST;
    }

    private static String fieldCode(FieldError fe) {
        String constraint = fe.getCode() == null ? "" : fe.getCode();
        return switch (constraint) {
            case "NotBlank", "NotNull", "NotEmpty" -> ErrorCode.REQUIRED;
            case "Email" -> ErrorCode.INVALID_EMAIL;
            case "PasswordLength" -> ErrorCode.PASSWORD_LENGTH;
            case "Size" -> ErrorCode.TOO_LONG;
            default -> ErrorCode.VALIDATION_FAILED;
        };
    }

    private static int priority(String code) {
        return FIELD_CODE_PRIORITY.getOrDefault(code, 3);
    }
}
