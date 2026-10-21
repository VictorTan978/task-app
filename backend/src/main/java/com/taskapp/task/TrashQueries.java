package com.taskapp.task;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * Reads of the trash (contract 0.16.0), native SQL with bind parameters only. "My trash" = trashed tasks of the lists
 * the user is a member of ({@code list_members}); one list when {@code listId} is given. Plain reads, no locks.
 */
@Repository
public class TrashQueries {

    /** Id and list of an expired trashed task (the purge locks the list before the task). */
    public record Expired(long id, long listId) {
    }

    @PersistenceContext
    private EntityManager em;

    private static final String MINE = " FROM tasks t JOIN list_members m ON m.list_id = t.list_id AND m.user_id = :userId"
            + " WHERE t.deleted_at IS NOT NULL";

    private static void scope(StringBuilder sql, Map<String, Object> params, long userId, Long listId) {
        params.put("userId", userId);
        if (listId != null) {
            sql.append(" AND t.list_id = :listId");
            params.put("listId", listId);
        }
    }

    /** Newest deleted first: {@code deleted_at} descending, then id descending; strictly after {@code after}. */
    @SuppressWarnings("unchecked")
    List<Task> findPage(long userId, Long listId, TrashCursor after, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT t.*").append(MINE);
        scope(sql, params, userId, listId);
        if (after != null) {
            sql.append(" AND (t.deleted_at < :at OR (t.deleted_at = :at AND t.id < :id))");
            params.put("at", after.deletedAt());
            params.put("id", after.id());
        }
        sql.append(" ORDER BY t.deleted_at DESC, t.id DESC LIMIT ").append(limit);
        Query query = em.createNativeQuery(sql.toString(), Task.class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }

    long count(long userId, Long listId) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(MINE);
        scope(sql, params, userId, listId);
        Query query = em.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    /** Ids of my trashed tasks above {@code afterId}, ascending (batches of "empty trash"). */
    @SuppressWarnings("unchecked")
    List<Long> findIds(long userId, Long listId, long afterId, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT t.id").append(MINE);
        scope(sql, params, userId, listId);
        sql.append(" AND t.id > :afterId ORDER BY t.id LIMIT ").append(limit);
        params.put("afterId", afterId);
        Query query = em.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        return ((List<Number>) query.getResultList()).stream().map(Number::longValue).toList();
    }

    /** The oldest trashed tasks deleted before {@code cutoff} (the purge; any list). Plain read. */
    @SuppressWarnings("unchecked")
    List<Expired> findExpired(Instant cutoff, int limit) {
        Query query = em.createNativeQuery("SELECT t.id, t.list_id FROM tasks t WHERE t.deleted_at < :cutoff"
                + " ORDER BY t.deleted_at, t.id LIMIT " + limit);
        query.setParameter("cutoff", cutoff);
        return ((List<Object[]>) query.getResultList()).stream()
                .map(r -> new Expired(((Number) r[0]).longValue(), ((Number) r[1]).longValue())).toList();
    }
}
