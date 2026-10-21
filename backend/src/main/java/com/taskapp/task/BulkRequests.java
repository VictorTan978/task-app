package com.taskapp.task;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.validation.BindingResult;
import org.springframework.validation.MapBindingResult;

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.task.dto.BulkAction;
import com.taskapp.task.dto.BulkActionRequest;
import com.taskapp.task.dto.BulkUndoCompleteItem;
import com.taskapp.task.dto.SetTaskDoneRequest;

import tools.jackson.databind.JsonNode;

/**
 * The checks of the bulk calls that bean validation can't express (contract 0.17.0): the number and uniqueness of the
 * ids, the {@code value} of each action, and per-item checks of the controller. All problems are collected and
 * reported together.
 */
final class BulkRequests {

    static final int MAX = 200;
    static final String COUNT_MESSAGE = "Select 1 to 200 tasks";

    private BulkRequests() {
    }

    static BindingResult errors() {
        return new MapBindingResult(new java.util.HashMap<>(), "bulkRequest");
    }

    static void throwIfAny(BindingResult errors) {
        if (errors.hasErrors()) {
            throw new InvalidFieldsException(errors);
        }
    }

    /** {@code ids} / {@code items}: present, 1-200, no null, no duplicate. */
    static void ids(BindingResult errors, String field, List<Long> ids) {
        if (ids == null || ids.stream().anyMatch(java.util.Objects::isNull)) {
            errors.rejectValue(field, "NotNull", "Select 1 to 200 tasks");
        } else if (ids.isEmpty() || ids.size() > MAX) {
            errors.rejectValue(field, "Min", COUNT_MESSAGE);
        } else if (new HashSet<>(ids).size() != ids.size()) {
            errors.rejectValue(field, "Duplicate", "The same task is listed twice");
        }
    }

    /** What an action needs besides the ids, parsed. */
    record Parsed(BulkAction action, TaskStatus status, Priority priority, String tag, Long listId) {
    }

    /** The action's {@code value}: missing = REQUIRED, wrong type or unknown enum = malformed body. */
    static Parsed action(BindingResult errors, BulkActionRequest request) {
        BulkAction action = request.action();
        JsonNode value = request.value();
        boolean missing = value == null || value.isNull();
        switch (action) {
            case SET_STATUS -> {
                if (missing) {
                    errors.rejectValue("value", "NotNull", "Status is required");
                    return null;
                }
                return new Parsed(action, enumOf(TaskStatus.class, value), null, null, null);
            }
            case SET_PRIORITY -> {
                if (missing) {
                    errors.rejectValue("value", "NotNull", "Priority is required");
                    return null;
                }
                return new Parsed(action, null, enumOf(Priority.class, value), null, null);
            }
            case ADD_TAG, REMOVE_TAG -> {
                if (missing) {
                    errors.rejectValue("value", "NotNull", "Tag is required");
                    return null;
                }
                if (!value.isString()) {
                    throw new MalformedRequestException("value must be a string");
                }
                String tag = value.asString();
                if (TagNames.isBlank(tag)) {
                    errors.rejectValue("value", "TagsNotBlank", "Tags can't be blank");
                } else if (TagNames.hasInvalidCharacters(tag)) {
                    errors.rejectValue("value", "TagsValidCharacters",
                            "Tags can't contain commas or control characters");
                } else if (TagNames.isTooLong(tag)) {
                    errors.rejectValue("value", "TagsMaxLength", "Tags must be 30 characters or fewer");
                } else {
                    return new Parsed(action, null, null, TagNames.normalize(tag), null);
                }
                return null;
            }
            case MOVE -> {
                if (missing) {
                    errors.rejectValue("value", "NotNull", "List is required");
                    return null;
                }
                if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                    throw new MalformedRequestException("value must be a list id");
                }
                return new Parsed(action, null, null, null, value.longValue());
            }
            default -> {
                return new Parsed(action, null, null, null, null);
            }
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, JsonNode value) {
        if (value.isString()) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(value.asString())) {
                    return constant;
                }
            }
        }
        throw new MalformedRequestException("value is not a " + type.getSimpleName());
    }

    /** A description over 2000 characters in item {@code index} (a {@code TextUpdate} can't carry a constraint). */
    static void patchDescription(BindingResult errors, int index, SetTaskDoneRequest patch) {
        if (patch != null && patch.description() != null && patch.description().value() != null
                && patch.description().value().length() > 2000) {
            errors.rejectValue("items", "Size", "Item " + (index + 1)
                    + ": Description must be 2,000 characters or fewer");
        }
    }

    /** {@code status} {@code DONE} can't be the status a reopened task goes back to. */
    static void undoItem(BindingResult errors, int index, BulkUndoCompleteItem item) {
        if (item.status() == TaskStatus.DONE) {
            errors.rejectValue("items", "Min", "Item " + (index + 1) + ": Status must be TODO or IN_PROGRESS");
        }
    }

    static Set<Long> distinct(List<Long> ids) {
        return new HashSet<>(ids);
    }
}
