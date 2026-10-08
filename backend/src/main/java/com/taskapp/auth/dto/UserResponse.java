package com.taskapp.auth.dto;

import com.taskapp.auth.User;

/** Public view of a user. Never carries the password or its hash. */
public record UserResponse(long id, String email, String displayName) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
