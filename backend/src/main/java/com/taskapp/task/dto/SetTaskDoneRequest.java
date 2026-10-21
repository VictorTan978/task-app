package com.taskapp.task.dto;

import java.util.List;

import com.taskapp.task.Priority;
import com.taskapp.task.TaskStatus;
import com.taskapp.task.validation.DoneOrStatusRequired;
import com.taskapp.task.validation.NotBlankIfPresent;
import com.taskapp.task.validation.RepeatFields;
import com.taskapp.task.validation.StatusAndDone;
import com.taskapp.task.validation.StatusMatchesDone;
import com.taskapp.task.validation.TagsMaxCount;
import com.taskapp.task.validation.TagsMaxLength;
import com.taskapp.task.validation.TagsNotBlank;
import com.taskapp.task.validation.TagsValidCharacters;
import com.taskapp.task.validation.ValidReminderValue;
import com.taskapp.task.validation.ValidRepeat;
import com.taskapp.task.validation.RepeatRequired;

import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of {@code PATCH /api/tasks/{id}}: any subset of the task's fields (contract 0.16.0; before it only
 * {@code done} / {@code status}). A field that is not sent is unchanged. JSON {@code null} clears
 * {@code description}, {@code dueDate} and {@code dueTime} ({@link TextUpdate}, {@link DateUpdate},
 * {@link DueTimeUpdate}) and means "unchanged" on every other field, as in {@code PUT}. At least one field must be
 * sent. {@code done} and {@code status} must agree.
 */
@DoneOrStatusRequired
@StatusMatchesDone
@ValidRepeat
@RepeatRequired
public record SetTaskDoneRequest(
        Boolean done,
        TaskStatus status,

        @NotBlankIfPresent
        @Size(max = 200, message = "Title must be 200 characters or fewer")
        String title,

        @JsonDeserialize(using = TextUpdateDeserializer.class)
        TextUpdate description,

        @JsonDeserialize(using = DateUpdateDeserializer.class)
        DateUpdate dueDate,

        @JsonDeserialize(using = DueTimeDeserializer.class)
        DueTimeUpdate dueTime,

        Priority priority,

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
        RepeatInput repeat) implements StatusAndDone, RepeatFields {

    public SetTaskDoneRequest {
        title = TaskText.title(title);
    }

    /** The request as it was before 0.16.0: only the status and/or its done mirror. */
    public SetTaskDoneRequest(Boolean done, TaskStatus status) {
        this(done, status, null, null, null, null, null, null, null, null, null);
    }

    /** Only {@code done} / {@code status} (the pre-0.16.0 request): handled by the original, lighter path. */
    public boolean onlyStatus() {
        return title == null && description == null && dueDate == null && dueTime == null && priority == null
                && tags == null && listId == null && reminder == null && repeat == null;
    }

    @Override
    public boolean hasChanges() {
        return !(onlyStatus() && status == null && done == null);
    }
}
