package com.taskapp.task.dto;

import java.util.List;

import tools.jackson.databind.annotation.JsonDeserialize;

/** Body of {@code POST /api/trash/restore} (0.17.0). 1-200 unique ids, checked by the controller. */
public record RestoreTasksRequest(
        @JsonDeserialize(contentUsing = StrictLongDeserializer.class)
        List<Long> ids) {
}
