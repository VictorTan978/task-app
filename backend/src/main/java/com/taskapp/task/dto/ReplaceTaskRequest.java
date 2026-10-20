package com.taskapp.task.dto;

import java.time.LocalDate;
import java.util.List;

import tools.jackson.databind.annotation.JsonDeserialize;
import com.taskapp.task.Priority;
import com.taskapp.task.TaskStatus;
import com.taskapp.task.validation.DueFields;
import com.taskapp.task.validation.RepeatFields;
import com.taskapp.task.validation.RepeatRequired;
import com.taskapp.task.validation.RequiresDueDate;
import com.taskapp.task.validation.StatusAndDone;
import com.taskapp.task.validation.StatusMatchesDone;
import com.taskapp.task.validation.TagsMaxCount;
import com.taskapp.task.validation.TagsMaxLength;
import com.taskapp.task.validation.TagsNotBlank;
import com.taskapp.task.validation.TagsValidCharacters;
import com.taskapp.task.validation.ValidReminderValue;
import com.taskapp.task.validation.ValidRepeat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Full replacement of title, description and due date: a null/omitted description or dueDate clears the field.
 * {@code priority}, {@code status}, {@code done} and {@code tags} are optional: null/omitted leaves them unchanged
 * (see {@link TaskStatus#resolve}). {@code tags} elements are kept raw here; the rules normalise them themselves.
 * {@code listId} (0.12.0): null or the task's current list = stays; another of the user's lists = move. Must be a
 * JSON integer. {@code dueTime} (0.14.0): not sent = unchanged, JSON null = cleared ({@link DueTimeUpdate}),
 * {@code HH:mm} = set; {@code reminder}: null = my reminder unchanged, {@code NONE} = remove, or an offset name.
 * {@code repeat} (0.15.0): null = unchanged, {@code "NEVER"} = remove the rule, an object = set it ({@link RepeatInput}).
 */
@StatusMatchesDone
@RequiresDueDate
@ValidRepeat
@RepeatRequired
public record ReplaceTaskRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 200, message = "Title must be 200 characters or fewer")
        String title,

        @Size(max = 2000, message = "Description must be 2,000 characters or fewer")
        String description,

        LocalDate dueDate,

        @JsonDeserialize(using = DueTimeDeserializer.class)
        DueTimeUpdate dueTime,

        Priority priority,

        TaskStatus status,

        Boolean done,

        @TagsNotBlank
        @TagsValidCharacters
        @TagsMaxLength
        @TagsMaxCount
        @JsonDeserialize(contentUsing = StrictStringDeserializer.class)
        List<String> tags,

        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long listId,

        @ValidReminderValue
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String reminder,

        @JsonDeserialize(using = RepeatDeserializer.class)
        RepeatInput repeat) implements StatusAndDone, DueFields, RepeatFields {

    public ReplaceTaskRequest {
        title = TaskText.title(title);
        description = TaskText.description(description);
    }

    /** The request as it was before 0.15.0: no repeat (unchanged). */
    public ReplaceTaskRequest(String title, String description, LocalDate dueDate, DueTimeUpdate dueTime,
            Priority priority, TaskStatus status, Boolean done, List<String> tags, Long listId, String reminder) {
        this(title, description, dueDate, dueTime, priority, status, done, tags, listId, reminder, null);
    }

    /** The request as it was before 0.14.0: no due time, no reminder. */
    public ReplaceTaskRequest(String title, String description, LocalDate dueDate, Priority priority,
            TaskStatus status, Boolean done, List<String> tags, Long listId) {
        this(title, description, dueDate, null, priority, status, done, tags, listId, null, null);
    }

    @Override
    public boolean setsRepeat() {
        return repeat != null && repeat.isRule();
    }

    @Override
    public boolean setsDueTime() {
        return dueTime != null && dueTime.value() != null;
    }
}
