package com.taskapp.task.dto;

import java.time.LocalDate;
import java.util.List;

import tools.jackson.databind.annotation.JsonDeserialize;
import com.taskapp.task.Priority;
import com.taskapp.task.TaskStatus;
import com.taskapp.task.validation.StatusAndDone;
import com.taskapp.task.validation.StatusMatchesDone;
import com.taskapp.task.validation.TagsMaxCount;
import com.taskapp.task.validation.TagsMaxLength;
import com.taskapp.task.validation.TagsNotBlank;
import com.taskapp.task.validation.TagsValidCharacters;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Full replacement of title, description and due date: a null/omitted description or dueDate clears the field.
 * {@code priority}, {@code status}, {@code done} and {@code tags} are optional: null/omitted leaves them unchanged
 * (see {@link TaskStatus#resolve}). {@code tags} elements are kept raw here; the rules normalise them themselves.
 * {@code listId} (0.12.0): null or the task's current list = stays; another of the user's lists = move. Must be a
 * JSON integer.
 */
@StatusMatchesDone
public record ReplaceTaskRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 200, message = "Title must be 200 characters or fewer")
        String title,

        @Size(max = 2000, message = "Description must be 2,000 characters or fewer")
        String description,

        LocalDate dueDate,

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
        Long listId) implements StatusAndDone {

    public ReplaceTaskRequest {
        title = TaskText.title(title);
        description = TaskText.description(description);
    }
}
