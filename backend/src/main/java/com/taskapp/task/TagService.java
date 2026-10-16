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

/** Per-user tags. Everything is scoped by the owner id from the session; another user's tags are never touched. */
@Service
public class TagService {

    private final TagRepository tagRepository;

    public TagService(TagRepository tagRepository) {
        this.tagRepository = tagRepository;
    }

    @Transactional(readOnly = true)
    public List<TagResponse> list(long ownerId) {
        return tagRepository.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .map(tag -> new TagResponse(tag.getName()))
                .toList();
    }

    /** Must be the first statement of every transaction that changes tags (see {@link TagRepository#lockOwner}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOwner(long ownerId) {
        tagRepository.lockOwner(ownerId);
    }

    /** The tag a {@code ?tag=} value refers to, if it is a valid name the owner has. */
    @Transactional(readOnly = true)
    public Optional<Long> findTagId(long ownerId, String raw) {
        if (!TagNames.isValid(raw)) {
            return Optional.empty();
        }
        return tagRepository.findByOwnerIdAndName(ownerId, TagNames.normalize(raw)).map(Tag::getId);
    }

    @Transactional(readOnly = true)
    public List<String> namesOf(long ownerId, long taskId) {
        return tagRepository.findNamesByTask(ownerId, taskId);
    }

    /** Tag names per task id for these of the owner's tasks (one query). Tasks without tags are absent. */
    @Transactional(readOnly = true)
    public Map<Long, List<String>> namesByTask(long ownerId, Collection<Long> taskIds) {
        Map<Long, List<String>> byTask = new HashMap<>();
        if (taskIds.isEmpty()) {
            return byTask;
        }
        for (TagRepository.TaskTagName row : tagRepository.findTaskTagNames(ownerId, taskIds)) {
            byTask.computeIfAbsent(row.getTaskId(), id -> new ArrayList<>()).add(row.getName());
        }
        return byTask;
    }

    /**
     * Replaces the task's tags with {@code rawNames} (already validated). Each name reuses the owner's existing tag
     * (stored spelling) or creates it; names the database considers equal end up as one tag id. Only the links that
     * change are written, then the removed tags no task uses any more are deleted, and the 200-tag cap is enforced.
     * Caller holds the owner lock and owns {@code taskId}.
     * <p>
     * Locks (REPEATABLE READ), see {@link TagRepository}: every read here is a plain non-locking read; tag and link
     * INSERTs take insert-intention locks; link and tag DELETEs hit one existing row by primary key (record lock, no
     * gap). Nothing takes a gap lock before the last INSERT, so other users' saves never deadlock with this one,
     * and the owner lock serialises this user's own saves.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> replaceTags(long ownerId, long taskId, List<String> rawNames) {
        Set<Long> current = new LinkedHashSet<>(tagRepository.findTagIdsByTask(taskId));
        Set<Long> wanted = new LinkedHashSet<>();
        boolean created = false;
        for (String name : TagNames.distinctNormalized(rawNames)) {
            Optional<Tag> existing = tagRepository.findByOwnerIdAndName(ownerId, name);
            if (existing.isPresent()) {
                wanted.add(existing.get().getId());
            } else {
                wanted.add(tagRepository.saveAndFlush(new Tag(ownerId, name)).getId());
                created = true;
            }
        }
        List<Long> removed = current.stream().filter(id -> !wanted.contains(id)).toList();
        for (Long tagId : removed) {
            tagRepository.unlink(taskId, tagId);
        }
        for (Long tagId : wanted) {
            if (!current.contains(tagId)) {
                tagRepository.link(taskId, tagId);
            }
        }
        deleteUnused(ownerId, removed);
        if (created && tagRepository.countByOwnerId(ownerId) > TagNames.MAX_PER_USER) {
            throw new TagLimitExceededException();
        }
        return namesOf(ownerId, taskId);
    }

    /** The task's tag ids (read before deleting the task, to clean up its tags afterwards). Plain read. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> tagIdsOf(long taskId) {
        return tagRepository.findTagIdsByTask(taskId);
    }

    /**
     * Deletes those of {@code tagIds} (tags of the owner that just lost a task) that no task uses any more, each by
     * primary key. Only these can have become unused, so there is no range delete over the owner's tags. Caller
     * holds the owner lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteUnused(long ownerId, Collection<Long> tagIds) {
        for (Long tagId : tagIds) {
            if (tagRepository.countLinks(tagId) == 0) {
                tagRepository.deleteTag(ownerId, tagId);
            }
        }
    }
}
