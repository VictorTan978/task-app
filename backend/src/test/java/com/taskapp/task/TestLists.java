package com.taskapp.task;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test fixture (V10): users inserted directly (not through register or Google sign-up) have no list, so tests that
 * write tasks or tags create the lists they need here.
 */
public final class TestLists {

    private TestLists() {
    }

    /** Creates the user's "My tasks" and returns its id. */
    public static long personal(JdbcTemplate jdbc, long ownerId) {
        jdbc.update("insert into lists (owner_id, name, personal) values (?, 'My tasks', true)", ownerId);
        return jdbc.queryForObject("select id from lists where owner_id = ? and personal", Long.class, ownerId);
    }

    /** Creates another (non-personal) list of the user and returns its id. */
    public static long other(JdbcTemplate jdbc, long ownerId, String name) {
        jdbc.update("insert into lists (owner_id, name, personal) values (?, ?, false)", ownerId, name);
        return jdbc.queryForObject("select max(id) from lists where owner_id = ?", Long.class, ownerId);
    }
}
