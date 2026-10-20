package com.taskapp.task.validation;

import com.taskapp.task.dto.RepeatInput;

/** A request that may carry a {@code repeat}. */
public interface RepeatFields {

    RepeatInput repeat();
}
