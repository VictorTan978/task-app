package com.taskapp.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * Native SQL, built from fixed fragments; every value is a bind parameter.
 * <ul>
 * <li>Rows: always {@code owner_id = :ownerId}; the filter ({@code done} for all/active/done, {@code status} for
 * in_progress), an optional join on the tag, and an optional search: {@code title} OR {@code description} LIKE
 * {@code %text%} with {@code %}, {@code _} and the escape character escaped. The columns' collation
 * (utf8mb4_0900_ai_ci) makes LIKE and the title order ignore case and accents.</li>
 * <li>Order: {@code done} first for filter all (not-done group first), then the sort's keys (see {@link TaskSort}).
 * The same key list builds ORDER BY and the keyset condition "after the cursor", so the two always agree.</li>
 * </ul>
 */
class TaskSearchRepositoryImpl implements TaskSearchRepository {

    /** LIKE escape character. Not a backslash: MySQL also treats that as a string escape. */
    static final char LIKE_ESCAPE = '!';

    private static final String PRIORITY_RANK =
            "CASE t.priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 WHEN 'LOW' THEN 2 ELSE 3 END";

    @PersistenceContext
    private EntityManager em;

    /** One ORDER BY key: SQL expression, direction, and the cursor's value for it (null = not compared). */
    private record Key(String sql, boolean ascending, Object value) {
    }

    @Override
    public List<Task> findPage(TaskSearchCriteria criteria, TaskCursor after, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT t.* FROM tasks t");
        appendFromAndWhere(sql, criteria, params);
        List<Key> keys = keys(criteria, after);
        if (after != null) {
            sql.append(" AND ").append(after(keys, params));
        }
        sql.append(" ORDER BY ");
        for (int i = 0; i < keys.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(keys.get(i).sql()).append(keys.get(i).ascending() ? "" : " DESC");
        }
        sql.append(" LIMIT ").append(limit);
        Query query = em.createNativeQuery(sql.toString(), Task.class);
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Task> tasks = query.getResultList();
        return tasks;
    }

    @Override
    public long countMatching(TaskSearchCriteria criteria) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM tasks t");
        appendFromAndWhere(sql, criteria, params);
        Query query = em.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    private static void appendFromAndWhere(StringBuilder sql, TaskSearchCriteria criteria, Map<String, Object> params) {
        if (criteria.tagId() != null) {
            sql.append(" JOIN task_tags tt ON tt.task_id = t.id AND tt.tag_id = :tagId");
            params.put("tagId", criteria.tagId());
        }
        sql.append(" WHERE t.owner_id = :ownerId");
        params.put("ownerId", criteria.ownerId());
        switch (criteria.filter()) {
            case ALL -> {
            }
            case ACTIVE -> sql.append(" AND t.done = FALSE");
            case DONE -> sql.append(" AND t.done = TRUE");
            case IN_PROGRESS -> sql.append(" AND t.status = 'IN_PROGRESS'");
        }
        if (criteria.search() != null) {
            sql.append(" AND (t.title LIKE :pattern ESCAPE '").append(LIKE_ESCAPE)
                    .append("' OR t.description LIKE :pattern ESCAPE '").append(LIKE_ESCAPE).append("')");
            params.put("pattern", containsPattern(criteria.search()));
        }
    }

    /** The ORDER BY keys, each with the cursor's value ({@code after} may be null: values unused). */
    private static List<Key> keys(TaskSearchCriteria criteria, TaskCursor after) {
        List<Key> keys = new ArrayList<>();
        if (criteria.filter() == TaskFilter.ALL) {
            keys.add(new Key("t.done", true, after == null ? null : after.done()));
        }
        boolean newestTies = true;
        switch (criteria.sort()) {
            case NEWEST -> {
            }
            case OLDEST -> newestTies = false;
            case DUE -> {
                keys.add(new Key("(t.due_date IS NULL)", true, after == null ? null : after.dueDate() == null ? 1 : 0));
                // Inside the "no due date" part every due_date is NULL: nothing to compare.
                keys.add(new Key("t.due_date", true, after == null ? null : after.dueDate()));
            }
            case PRIORITY -> keys.add(new Key(PRIORITY_RANK, true, after == null ? null : rank(after.priority())));
            case TITLE -> keys.add(new Key("t.title", true, after == null ? null : after.title()));
        }
        keys.add(new Key("t.created_at", !newestTies, after == null ? null : after.createdAt()));
        keys.add(new Key("t.id", !newestTies, after == null ? null : after.id()));
        return keys;
    }

    /**
     * Rows strictly after the cursor in the keys' order: {@code k1 > v1 OR (k1 = v1 AND (k2 > v2 OR ...))}, with
     * {@code <} for descending keys. Keys without a value are skipped.
     */
    private static String after(List<Key> keys, Map<String, Object> params) {
        String condition = null;
        for (int i = keys.size() - 1; i >= 0; i--) {
            Key key = keys.get(i);
            if (key.value() == null) {
                continue;
            }
            String param = ":k" + i;
            params.put("k" + i, key.value());
            String beyond = key.sql() + (key.ascending() ? " > " : " < ") + param;
            condition = condition == null ? beyond
                    : "(" + beyond + " OR (" + key.sql() + " = " + param + " AND " + condition + "))";
        }
        return condition;
    }

    /** Rank used by {@link #PRIORITY_RANK}: HIGH 0, MEDIUM 1, LOW 2, NONE 3. */
    static int rank(Priority priority) {
        return switch (priority) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
            case NONE -> 3;
        };
    }

    /** A LIKE pattern matching values that contain {@code text} literally ({@code %}, {@code _}, {@code \} too). */
    static String containsPattern(String text) {
        StringBuilder pattern = new StringBuilder(text.length() + 2).append('%');
        for (char c : text.toCharArray()) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }
}
