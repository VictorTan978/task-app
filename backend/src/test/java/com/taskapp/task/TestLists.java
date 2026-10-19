package com.taskapp.task;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test fixture: users inserted directly (not through register or Google sign-up) have no list, so tests that write
 * tasks or tags create the lists they need here. Like {@link ListService}, every list gets its owner's membership row
 * (V11).
 */
public final class TestLists {

    private TestLists() {
    }

    /** Creates the user's "My tasks" (with the owner's membership) and returns its id. */
    public static long personal(JdbcTemplate jdbc, long ownerId) {
        jdbc.update("insert into lists (owner_id, name, personal) values (?, 'My tasks', true)", ownerId);
        long id = jdbc.queryForObject("select id from lists where owner_id = ? and personal", Long.class, ownerId);
        member(jdbc, id, ownerId);
        return id;
    }

    /** Creates another (non-personal) list of the user (with the owner's membership) and returns its id. */
    public static long other(JdbcTemplate jdbc, long ownerId, String name) {
        jdbc.update("insert into lists (owner_id, name, personal) values (?, ?, false)", ownerId, name);
        long id = jdbc.queryForObject("select max(id) from lists where owner_id = ?", Long.class, ownerId);
        member(jdbc, id, ownerId);
        return id;
    }

    /** Adds {@code userId} as a member of the list (as an accepted invitation would). */
    public static void member(JdbcTemplate jdbc, long listId, long userId) {
        jdbc.update("insert into list_members (list_id, user_id) values (?, ?)", listId, userId);
    }
}
