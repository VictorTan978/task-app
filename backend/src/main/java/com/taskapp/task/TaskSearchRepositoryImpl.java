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
 * <li>Rows: always {@code list_id = :listId}; the filter ({@code done} for all/active/done, {@code status} for
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

    /** The cursor's due time as text: a bound LocalTime would be shifted by the JVM zone (see WallClockTimeType). */
    private static final java.time.format.DateTimeFormatter TIME_PARAM =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

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
        orderBy(sql, keys);
        sql.append(" LIMIT ").append(limit);
        Query query = em.createNativeQuery(sql.toString(), Task.class);
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Task> tasks = query.getResultList();
        return tasks;
    }

    private static void orderBy(StringBuilder sql, List<Key> keys) {
        sql.append(" ORDER BY ");
        for (int i = 0; i < keys.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(keys.get(i).sql()).append(keys.get(i).ascending() ? "" : " DESC");
        }
    }

    /** One group (not done / done) of a list, in "My order": the same keys as {@link #findPage} with {@code manual}. */
    private static TaskSearchCriteria group(long listId, boolean done) {
        return new TaskSearchCriteria(listId, done ? TaskFilter.DONE : TaskFilter.ACTIVE, TaskSort.MANUAL, null, null);
    }

    @Override
    public List<GroupRow> manualGroup(long listId, boolean done) {
        TaskSearchCriteria criteria = group(listId, done);
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT t.id, t.position FROM tasks t");
        appendFromAndWhere(sql, criteria, params);
        orderBy(sql, keys(criteria, null));
        Query query = em.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        List<GroupRow> rows = new ArrayList<>();
        for (Object row : query.getResultList()) {
            Object[] columns = (Object[]) row;
            rows.add(new GroupRow(((Number) columns[0]).longValue(),
                    columns[1] == null ? null : ((Number) columns[1]).longValue()));
        }
        return rows;
    }

    @Override
    public void lockGroup(long listId, boolean done) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT t.id FROM tasks t");
        appendFromAndWhere(sql, group(listId, done), params);
        sql.append(" ORDER BY t.id FOR UPDATE");
        Query query = em.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        query.getResultList();
    }

    /** Ids per UPDATE statement of {@link #assignPositions}. */
    private static final int ASSIGN_CHUNK = 200;

    @Override
    public void assignPositions(List<Long> orderedIds, long step) {
        em.flush();
        for (int from = 0; from < orderedIds.size(); from += ASSIGN_CHUNK) {
            List<Long> chunk = orderedIds.subList(from, Math.min(orderedIds.size(), from + ASSIGN_CHUNK));
            StringBuilder sql = new StringBuilder("UPDATE tasks SET position = CASE id");
            for (int i = 0; i < chunk.size(); i++) {
                sql.append(" WHEN ?").append(2 * i + 1).append(" THEN ?").append(2 * i + 2);
            }
            sql.append(" END WHERE id IN (");
            for (int i = 0; i < chunk.size(); i++) {
                sql.append(i == 0 ? "" : ", ").append('?').append(2 * chunk.size() + i + 1);
            }
            Query query = em.createNativeQuery(sql.append(')').toString());
            for (int i = 0; i < chunk.size(); i++) {
                query.setParameter(2 * i + 1, chunk.get(i));
                query.setParameter(2 * i + 2, (from + i + 1) * step);
                query.setParameter(2 * chunk.size() + i + 1, chunk.get(i));
            }
            query.executeUpdate();
        }
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
        sql.append(" WHERE t.list_id = :listId AND t.deleted_at IS NULL");
        params.put("listId", criteria.listId());
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
                // Inside one date: timed tasks first by time, then date-only tasks (every due_time NULL: nothing to
                // compare). Inside "no due date" both are constant, so these keys never decide anything there.
                keys.add(new Key("(t.due_time IS NULL)", true, after == null ? null : after.dueTime() == null ? 1 : 0));
                keys.add(new Key("t.due_time", true, after == null || after.dueTime() == null ? null : after.dueTime().format(TIME_PARAM)));
            }
            case PRIORITY -> keys.add(new Key(PRIORITY_RANK, true, after == null ? null : rank(after.priority())));
            case TITLE -> keys.add(new Key("t.title", true, after == null ? null : after.title()));
            case MANUAL -> {
                // Positioned tasks first; inside the unpositioned part every position is NULL: nothing to compare.
                keys.add(new Key("(t.position IS NULL)", true, after == null ? null : after.position() == null ? 1 : 0));
                keys.add(new Key("t.position", true, after == null ? null : after.position()));
            }
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
