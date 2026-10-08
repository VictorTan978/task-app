package com.taskapp.security;

import java.io.Serializable;
import java.security.Principal;

/** Principal stored in the session's SecurityContext. Contains no credentials. */
public record AuthenticatedUser(long id, String email, String displayName) implements Principal, Serializable {

    @Override
    public String getName() {
        return email;
    }
}
