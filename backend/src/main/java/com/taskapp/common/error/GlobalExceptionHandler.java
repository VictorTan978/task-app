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
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.taskapp.admin.ActorDisabledException;
import com.taskapp.admin.CannotModifySelfException;
import com.taskapp.admin.InsufficientRoleException;
import com.taskapp.admin.NotAdminException;
import com.taskapp.admin.UserNotFoundException;
import com.taskapp.auth.AccountDisabledException;
import com.taskapp.auth.AlreadyVerifiedException;
import com.taskapp.auth.ConnectionNotFoundException;
import com.taskapp.auth.DisplayNameTakenException;
import com.taskapp.auth.EmailMismatchException;
import com.taskapp.auth.EmailTokenInvalidException;
import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.auth.InvalidCredentialsException;
import com.taskapp.auth.PasswordRequiredException;
import com.taskapp.auth.RegistrationConflictException;
import com.taskapp.auth.UnauthenticatedException;
import com.taskapp.auth.WrongPasswordException;
import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;
import com.taskapp.security.ratelimit.RateLimitedException;
import com.taskapp.task.TagLimitExceededException;
import com.taskapp.task.TaskNotFoundException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns every error raised in the MVC layer into an {@code application/problem+json} body with a {@code code}.
 * Errors raised by the security filter chain (401/403) are written by the handlers in {@code com.taskapp.security}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String EMAIL_TAKEN_MESSAGE = "An account with this email already exists";
    private static final String DISPLAY_NAME_TAKEN_MESSAGE = "This display name is already taken";

    /** Lower value wins when one field has several violations. Tags: REQUIRED, INVALID_CHARACTERS, TOO_LONG, TOO_MANY. */
    private static final Map<String, Integer> FIELD_CODE_PRIORITY = Map.of(
            ErrorCode.REQUIRED, 0,
            ErrorCode.INVALID_EMAIL, 1,
            ErrorCode.PASSWORD_LENGTH, 1,
            ErrorCode.OUT_OF_RANGE, 1,
            ErrorCode.INVALID_ROLE, 1,
            ErrorCode.INVALID_CHARACTERS, 1,
            ErrorCode.TOO_LONG, 2,
            ErrorCode.TOO_MANY, 3);

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

    /** Display name change: same 409 body as a register conflict on the display name only. */
    @ExceptionHandler(DisplayNameTakenException.class)
    ResponseEntity<ProblemDetail> handleDisplayNameTaken(DisplayNameTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Problems.of(HttpStatus.CONFLICT,
                ErrorCode.DISPLAY_NAME_TAKEN, DISPLAY_NAME_TAKEN_MESSAGE, List.of(new ApiFieldError(
                        "displayName", ErrorCode.DISPLAY_NAME_TAKEN, DISPLAY_NAME_TAKEN_MESSAGE))));
    }

    /** Wrong confirmation password on /api/account/*: 400 (never 401) with one field error. */
    @ExceptionHandler(WrongPasswordException.class)
    ResponseEntity<ProblemDetail> handleWrongPassword(WrongPasswordException ex, HttpServletRequest request) {
        WrongPasswordException.Action action = ex.action();
        SecurityEventLogger.log(SecurityEvent.PASSWORD_CHECK_FAILURE, "action", action.logValue(), request,
                ex.email());
        return ResponseEntity.badRequest().body(Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.WRONG_PASSWORD,
                action.message(), List.of(new ApiFieldError(action.field(), ErrorCode.WRONG_PASSWORD,
                        action.message()))));
    }

    /**
     * Delete account without a password, wrong email: 400 with one field error. Logged like a wrong password, and
     * counted on the same per-user limit by the service.
     */
    @ExceptionHandler(EmailMismatchException.class)
    ResponseEntity<ProblemDetail> handleEmailMismatch(EmailMismatchException ex, HttpServletRequest request) {
        SecurityEventLogger.log(SecurityEvent.PASSWORD_CHECK_FAILURE, "action", "delete_account_email", request,
                ex.email());
        return ResponseEntity.badRequest().body(Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.EMAIL_MISMATCH,
                EmailMismatchException.MESSAGE, List.of(new ApiFieldError("email", ErrorCode.EMAIL_MISMATCH,
                        EmailMismatchException.MESSAGE))));
    }

    /** The account has no password: change password and disconnect Google refuse. */
    @ExceptionHandler(PasswordRequiredException.class)
    ResponseEntity<ProblemDetail> handlePasswordRequired(PasswordRequiredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                Problems.of(HttpStatus.CONFLICT, ErrorCode.PASSWORD_REQUIRED, ex.getMessage()));
    }

    @ExceptionHandler(ConnectionNotFoundException.class)
    ResponseEntity<ProblemDetail> handleConnectionNotFound(ConnectionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                Problems.of(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException ex,
            HttpServletRequest request) {
        SecurityEventLogger.log(SecurityEvent.LOGIN_FAILURE, "reason",
                ex.reason() == null ? null : ex.reason().logValue(), request, ex.email());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Problems.of(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Invalid email or password"));
    }

    /**
     * Login with the correct password for a disabled account (only after the password matched, so it reveals nothing
     * to someone without the password): 403, nothing signed in.
     */
    @ExceptionHandler(AccountDisabledException.class)
    ResponseEntity<ProblemDetail> handleAccountDisabled(AccountDisabledException ex, HttpServletRequest request) {
        SecurityEventLogger.log(SecurityEvent.LOGIN_FAILURE, "reason", "account_disabled", request, ex.email());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                Problems.of(HttpStatus.FORBIDDEN, ErrorCode.ACCOUNT_DISABLED, ex.getMessage()));
    }

    // ---- admin endpoints (specs/roles-admin.md); refusals are audited by AdminUserService ----

    @ExceptionHandler(CannotModifySelfException.class)
    ResponseEntity<ProblemDetail> handleCannotModifySelf(CannotModifySelfException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                Problems.of(HttpStatus.CONFLICT, ErrorCode.CANNOT_MODIFY_SELF, ex.getMessage()));
    }

    @ExceptionHandler(InsufficientRoleException.class)
    ResponseEntity<ProblemDetail> handleInsufficientRole(InsufficientRoleException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                Problems.of(HttpStatus.FORBIDDEN, ErrorCode.INSUFFICIENT_ROLE, ex.getMessage()));
    }

    @ExceptionHandler(NotAdminException.class)
    ResponseEntity<ProblemDetail> handleNotAdmin(NotAdminException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                Problems.of(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, ex.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<ProblemDetail> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                Problems.of(HttpStatus.NOT_FOUND, ErrorCode.USER_NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(ActorDisabledException.class)
    ResponseEntity<ProblemDetail> handleActorDisabled(ActorDisabledException ex, HttpServletRequest request) {
        SecurityEventLogger.log(SecurityEvent.UNAUTHENTICATED, request, SecurityEventLogger.currentUserEmail());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Problems.of(HttpStatus.UNAUTHORIZED, ErrorCode.ACCOUNT_DISABLED, ex.getMessage()));
    }

    /** Every rate-limit refusal (per-IP, per-email and per-user) ends up here. */
    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> handleRateLimited(RateLimitedException ex, HttpServletRequest request) {
        SecurityEventLogger.log(SecurityEvent.RATE_LIMITED, "limit",
                ex.limit() == null ? null : ex.limit().logValue(), request, ex.email());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .body(Problems.of(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                        "Too many attempts. Please try again later."));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    ResponseEntity<ProblemDetail> handleUnauthenticated(UnauthenticatedException ex, HttpServletRequest request) {
        SecurityEventLogger.log(SecurityEvent.UNAUTHENTICATED, request, SecurityEventLogger.currentUserEmail());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Problems.of(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Authentication required"));
    }

    /** Every invalid emailed link gives the same 400; the real reason only goes to the security log. */
    @ExceptionHandler(EmailTokenInvalidException.class)
    ResponseEntity<ProblemDetail> handleEmailTokenInvalid(EmailTokenInvalidException ex, HttpServletRequest request) {
        SecurityEventLogger.log(SecurityEvent.EMAIL_TOKEN_REJECTED, "reason", ex.reason(), request, ex.email());
        return ResponseEntity.badRequest().body(Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.TOKEN_INVALID,
                "This link is invalid or has expired."));
    }

    @ExceptionHandler(AlreadyVerifiedException.class)
    ResponseEntity<ProblemDetail> handleAlreadyVerified(AlreadyVerifiedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Problems.of(HttpStatus.CONFLICT,
                ErrorCode.ALREADY_VERIFIED, "Your email address is already confirmed."));
    }

    /** Validation run by a service (e.g. after the reset token check): same body as a {@code @Valid} failure. */
    @ExceptionHandler(InvalidFieldsException.class)
    ResponseEntity<ProblemDetail> handleInvalidFields(InvalidFieldsException ex) {
        return ResponseEntity.badRequest().body(validationFailed(ex.bindingResult()));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    ResponseEntity<ProblemDetail> handleTaskNotFound(TaskNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                Problems.of(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Task not found"));
    }

    /** Only reached when every other field was valid, so it is the only entry. Nothing was saved (rolled back). */
    @ExceptionHandler(TagLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleTagLimit(TagLimitExceededException ex) {
        return ResponseEntity.badRequest().body(Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Validation failed", List.of(new ApiFieldError("tags", ErrorCode.TAG_LIMIT,
                        TagLimitExceededException.MESSAGE))));
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
        return ResponseEntity.badRequest().body(validationFailed(ex.getBindingResult()));
    }

    /** One entry per field (the highest-priority code wins), sorted by field name. */
    private static ProblemDetail validationFailed(BindingResult bindingResult) {
        Map<String, ApiFieldError> byField = new LinkedHashMap<>();
        for (FieldError fe : bindingResult.getFieldErrors()) {
            ApiFieldError candidate = new ApiFieldError(fe.getField(), fieldCode(fe), fe.getDefaultMessage());
            byField.merge(fe.getField(), candidate, (current, next) ->
                    priority(next.code()) < priority(current.code()) ? next : current);
        }
        List<ApiFieldError> errors = byField.values().stream()
                .sorted(Comparator.comparing(ApiFieldError::field))
                .toList();
        return Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Validation failed", errors);
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
            case "NotBlank", "NotNull", "NotEmpty", "TagsNotBlank", "DoneOrStatusRequired" -> ErrorCode.REQUIRED;
            case "TagsValidCharacters" -> ErrorCode.INVALID_CHARACTERS;
            case "TagsMaxLength" -> ErrorCode.TOO_LONG;
            case "TagsMaxCount" -> ErrorCode.TOO_MANY;
            case "StatusMatchesDone" -> ErrorCode.STATUS_CONFLICT;
            case "InvalidCursor" -> ErrorCode.INVALID_CURSOR;
            case "Email" -> ErrorCode.INVALID_EMAIL;
            case "PasswordLength" -> ErrorCode.PASSWORD_LENGTH;
            case "Size" -> ErrorCode.TOO_LONG;
            case "Min", "Max" -> ErrorCode.OUT_OF_RANGE;
            case "InvalidRole" -> ErrorCode.INVALID_ROLE;
            default -> ErrorCode.VALIDATION_FAILED;
        };
    }

    private static int priority(String code) {
        return FIELD_CODE_PRIORITY.getOrDefault(code, 4);
    }
}
