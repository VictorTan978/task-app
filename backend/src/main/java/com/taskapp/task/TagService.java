package com.taskapp.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.taskapp.task.dto.TagResponse;

/**
 * Per-list tags (0.12.0: they were per user). Everything is scoped by a list id the caller has already checked to be
 * the signed-in user's; another list's tags are never touched. Writes run with the list's row lock held
 * ({@link ListRepository#lockList}), which serialises every change of that list's tags.
 */
@Service
public class TagService {

    private final TagRepository tagRepository;
    private final ListService listService;

    public TagService(TagRepository tagRepository, ListService listService) {
        this.tagRepository = tagRepository;
        this.listService = listService;
    }

    /** {@code GET /api/tags}: the tags of the list {@code rawList} (missing/empty = "My tasks"), else 404. */
    @Transactional(readOnly = true)
    public List<TagResponse> list(long userId, String rawList) {
        long listId = listService.resolve(userId, rawList);
        return tagRepository.findByListIdOrderByNameAsc(listId).stream()
                .map(tag -> new TagResponse(tag.getName()))
                .toList();
    }

    /** The tag a {@code ?tag=} value refers to, if it is a valid name the list has. */
    @Transactional(readOnly = true)
    public Optional<Long> findTagId(long listId, String raw) {
        if (!TagNames.isValid(raw)) {
            return Optional.empty();
        }
        return tagRepository.findByListIdAndName(listId, TagNames.normalize(raw)).map(Tag::getId);
    }

    @Transactional(readOnly = true)
    public List<String> namesOf(long listId, long taskId) {
        return tagRepository.findNamesByTask(listId, taskId);
    }

    /** Tag names per task id for these tasks of the list (one query). Tasks without tags are absent. */
    @Transactional(readOnly = true)
    public Map<Long, List<String>> namesByTask(long listId, Collection<Long> taskIds) {
        Map<Long, List<String>> byTask = new HashMap<>();
        if (taskIds.isEmpty()) {
            return byTask;
        }
        for (TagRepository.TaskTagName row : tagRepository.findTaskTagNames(listId, taskIds)) {
            byTask.computeIfAbsent(row.getTaskId(), id -> new ArrayList<>()).add(row.getName());
        }
        return byTask;
    }

    /**
     * Replaces the task's tags with {@code rawNames} (already validated). Each name reuses the list's existing tag
     * (stored spelling) or creates it with {@code ownerId} = the list's owner; names the database considers equal end
     * up as one tag id. Only the links that change are written, then the removed tags no task uses any more are
     * deleted, and the 200-tag cap is enforced. Caller holds the list lock and the task is in {@code listId}.
     * <p>
     * Locks (REPEATABLE READ), see {@link TagRepository}: every read here is a plain non-locking read; tag and link
     * INSERTs take insert-intention locks; link and tag DELETEs hit one existing row by primary key (record lock, no
     * gap). Nothing takes a gap lock before the last INSERT, so saves in other lists never deadlock with this one,
     * and the list lock serialises the saves of this list.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> replaceTags(long ownerId, long listId, long taskId, List<String> rawNames) {
        Set<Long> current = new LinkedHashSet<>(tagRepository.findTagIdsByTask(taskId));
        Resolved wanted = resolve(ownerId, listId, TagNames.distinctNormalized(rawNames));
        List<Long> removed = current.stream().filter(id -> !wanted.ids().contains(id)).toList();
        for (Long tagId : removed) {
            tagRepository.unlink(taskId, tagId);
        }
        for (Long tagId : wanted.ids()) {
            if (!current.contains(tagId)) {
                tagRepository.link(taskId, tagId);
            }
        }
        deleteUnused(listId, removed);
        checkLimit(listId, wanted.created());
        return namesOf(listId, taskId);
    }

    /**
     * The tag part of moving a task from {@code sourceListId} to {@code targetListId} (the task row already points at
     * the target). Its tags afterwards: {@code rawNames} (already validated) if sent, else its current names. Each
     * reuses the target's tag with that name (the target's spelling) or is created in the target with
     * {@code ownerId} = the target's owner. Every source link is removed, then the source tags no task uses any more
     * are deleted. Over 200 tags in the target throws {@link TagLimitExceededException} (the caller's transaction
     * rolls back, so nothing changes). Caller holds both list locks. Same lock rules as {@link #replaceTags}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> moveTags(long ownerId, long sourceListId, long targetListId, long taskId,
            List<String> rawNames) {
        List<String> names = rawNames != null ? TagNames.distinctNormalized(rawNames)
                : tagRepository.findNamesByTask(sourceListId, taskId);
        List<Long> current = tagRepository.findTagIdsByTask(taskId);
        Resolved wanted = resolve(ownerId, targetListId, names);
        for (Long tagId : current) {
            tagRepository.unlink(taskId, tagId);
        }
        for (Long tagId : wanted.ids()) {
            tagRepository.link(taskId, tagId);
        }
        deleteUnused(sourceListId, current);
        checkLimit(targetListId, wanted.created());
        return namesOf(targetListId, taskId);
    }

    /** The task's tag ids (read before deleting the task, to clean up its tags afterwards). Plain read. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> tagIdsOf(long taskId) {
        return tagRepository.findTagIdsByTask(taskId);
    }

    /**
     * Deletes those of {@code tagIds} (tags of the list that just lost a task) that no task uses any more, each by
     * primary key. Only these can have become unused, so there is no range delete over the list's tags. Caller
     * holds the list lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteUnused(long listId, Collection<Long> tagIds) {
        for (Long tagId : tagIds) {
            if (tagRepository.countLinks(tagId) == 0) {
                tagRepository.deleteTag(listId, tagId);
            }
        }
    }

    /** Tag ids for these normalised, distinct names in the list, creating the missing ones. */
    private Resolved resolve(long ownerId, long listId, List<String> names) {
        Set<Long> ids = new LinkedHashSet<>();
        boolean created = false;
        for (String name : names) {
            Optional<Tag> existing = tagRepository.findByListIdAndName(listId, name);
            if (existing.isPresent()) {
                ids.add(existing.get().getId());
            } else {
                ids.add(tagRepository.saveAndFlush(new Tag(ownerId, listId, name)).getId());
                created = true;
            }
        }
        return new Resolved(ids, created);
    }

    private void checkLimit(long listId, boolean created) {
        if (created && tagRepository.countByListId(listId) > TagNames.MAX_PER_LIST) {
            throw new TagLimitExceededException();
        }
    }

    private record Resolved(Set<Long> ids, boolean created) {
    }
}
