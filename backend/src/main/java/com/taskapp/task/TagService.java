package com.taskapp.task;

import java.util.ArrayList;
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

    /** Tag names per task id for all the owner's tasks (one query). Tasks without tags are absent. */
    @Transactional(readOnly = true)
    public Map<Long, List<String>> namesByTask(long ownerId) {
        Map<Long, List<String>> byTask = new HashMap<>();
        for (TagRepository.TaskTagName row : tagRepository.findTaskTagNames(ownerId)) {
            byTask.computeIfAbsent(row.getTaskId(), id -> new ArrayList<>()).add(row.getName());
        }
        return byTask;
    }

    /**
     * Replaces the task's tags with {@code rawNames} (already validated). Each name reuses the owner's existing tag
     * (stored spelling) or creates it; names the database considers equal end up as one tag id. Then deletes the
     * owner's unused tags and enforces the 200-tag cap. Caller holds the owner lock and owns {@code taskId}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> replaceTags(long ownerId, long taskId, List<String> rawNames) {
        Set<Long> tagIds = new LinkedHashSet<>();
        boolean created = false;
        for (String name : TagNames.distinctNormalized(rawNames)) {
            Optional<Tag> existing = tagRepository.findByOwnerIdAndName(ownerId, name);
            if (existing.isPresent()) {
                tagIds.add(existing.get().getId());
            } else {
                tagIds.add(tagRepository.saveAndFlush(new Tag(ownerId, name)).getId());
                created = true;
            }
        }
        tagRepository.unlinkAll(taskId);
        for (Long tagId : tagIds) {
            tagRepository.link(taskId, tagId);
        }
        tagRepository.deleteUnused(ownerId);
        if (created && tagRepository.countByOwnerId(ownerId) > TagNames.MAX_PER_USER) {
            throw new TagLimitExceededException();
        }
        return namesOf(ownerId, taskId);
    }

    /** Deletes the owner's tags that no task uses (after a task was deleted). Caller holds the owner lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteUnused(long ownerId) {
        tagRepository.deleteUnused(ownerId);
    }
}
