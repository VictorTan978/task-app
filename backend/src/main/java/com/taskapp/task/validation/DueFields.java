package com.taskapp.task.validation;

import java.time.LocalDate;

/** A request that may carry a due time and a reminder next to the due date. */
public interface DueFields {

    LocalDate dueDate();

    /** A non-null due time is being set (not unchanged, not cleared). */
    boolean setsDueTime();

    /** The raw {@code reminder} value (null = unchanged). */
    String reminder();
}
