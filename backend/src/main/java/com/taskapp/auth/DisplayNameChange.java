package com.taskapp.auth;

import com.taskapp.auth.dto.UserResponse;

/** Result of a display name change: the updated user, and whether the stored name actually changed. */
public record DisplayNameChange(UserResponse user, boolean changed) {
}
