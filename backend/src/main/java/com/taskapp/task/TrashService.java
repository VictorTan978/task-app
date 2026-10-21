package com.taskapp.task;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.MapBindingResult;

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.reminder.ReminderService;
import com.taskapp.task.dto.EmptyTrashResult;
import com.taskapp.task.dto.TaskResponse;
import com.taskapp.task.dto.TrashItem;
import com.taskapp.task.dto.TrashPage;

/**
 * The trash (contract 0.16.0): the list, "empty trash" and the purge. Restore and delete forever of one task live in
 * {@link TaskService}, which has the task write locking; the loops here call them one task at a time, each in its own
 * transaction, so a long trash never holds locks for long. Any member of a list may do all of it (AC6).
 */
@Service
public class TrashService {

    /** How long a task stays in the trash. */
    public static final Duration RETENTION = Duration.ofDays(30);

    /** Fixed page size of {@code GET /api/trash}. */
    static final int PAGE_SIZE = 50;

    private static final int BATCH = 100;

    /** Batches per purge run (a run is hourly; whatever is left waits for the next one). */
    private static final int MAX_PURGE_BATCHES = 50;

    private final TrashQueries queries;
    private final TaskRepository taskRepository;
    private final TagService tagService;
    private final ListService listService;
    private final ReminderService reminders;
    private final TaskService tasks;
    private final Clock clock;

    public TrashService(TrashQueries queries, TaskRepository taskRepository, TagService tagService,
            ListService listService, ReminderService reminders, TaskService tasks, Clock clock) {
        this.queries = queries;
        this.taskRepository = taskRepository;
        this.tagService = tagService;
        this.listService = listService;
        this.reminders = reminders;
        this.tasks = tasks;
        this.clock = clock;
    }

    /**
     * One page, newest deleted first. {@code listRaw}: missing or empty = every list I am a member of, else it must be
     * one of them (404 {@code LIST_NOT_FOUND}, checked first). A cursor that can't be read or was issued for another
     * {@code list} is 400 {@code cursor} / {@code INVALID_CURSOR}.
     */
    @Transactional(readOnly = true)
    public TrashPage list(long userId, String listRaw, String cursorRaw) {
        Long listId = scope(userId, listRaw);
        byte[] fingerprint = TrashCursor.fingerprint(listId);
        TrashCursor after = null;
        if (cursorRaw != null && !cursorRaw.isEmpty()) {
            after = TrashCursor.decode(cursorRaw, fingerprint).orElse(null);
            if (after == null) {
                MapBindingResult errors = new MapBindingResult(new HashMap<>(), "trashQuery");
                errors.rejectValue("cursor", TaskService.INVALID_CURSOR_CONSTRAINT, TaskService.CURSOR_MESSAGE);
                throw new InvalidFieldsException(errors);
            }
        }
        List<Task> rows = queries.findPage(userId, listId, after, PAGE_SIZE + 1);
        boolean more = rows.size() > PAGE_SIZE;
        List<Task> page = more ? rows.subList(0, PAGE_SIZE) : rows;
        long total = after == null && !more ? page.size() : queries.count(userId, listId);
        String next = more ? TrashCursor.after(page.get(page.size() - 1)).encode(fingerprint) : null;

        List<Long> ids = page.stream().map(Task::getId).toList();
        Map<Long, List<String>> tags = new HashMap<>();
        page.stream().collect(Collectors.groupingBy(Task::getListId)).forEach((list, group) ->
                tags.putAll(tagService.namesByTask(list, group.stream().map(Task::getId).toList())));
        Map<Long, String> names = new HashMap<>();
        List<Long> people = page.stream().flatMap(t -> java.util.stream.Stream.of(t.getOwnerId(), t.getDeletedBy()))
                .filter(Objects::nonNull).distinct().toList();
        if (!people.isEmpty()) {
            taskRepository.findDisplayNames(people).forEach(row -> names.put(row.getId(), row.getDisplayName()));
        }
        Map<Long, ReminderService.Mine> mine = reminders.mine(userId, ids);
        List<TrashItem> items = page.stream().map(task -> new TrashItem(
                TaskResponse.from(task, tags.getOrDefault(task.getId(), List.of()), names.get(task.getOwnerId()),
                        mine.get(task.getId())),
                task.getDeletedAt(),
                task.getDeletedBy() == null || !names.containsKey(task.getDeletedBy()) ? null
                        : new TrashItem.DeletedBy(names.get(task.getDeletedBy())),
                task.getDeletedAt().plus(RETENTION))).toList();
        return new TrashPage(items, total, next);
    }

    /**
     * {@code POST /api/trash/empty}: deletes for good every trashed task I can see (of one list when {@code listRaw}
     * is given). The list is checked first (404). Ids are read in batches, each task deleted in its own transaction; a
     * task restored or deleted by someone else meanwhile is skipped. Returns how many this call deleted.
     */
    public EmptyTrashResult empty(long userId, String listRaw) {
        Long listId = scope(userId, listRaw);
        long deleted = 0;
        long afterId = 0;
        while (true) {
            List<Long> ids = queries.findIds(userId, listId, afterId, BATCH);
            if (ids.isEmpty()) {
                return new EmptyTrashResult(deleted);
            }
            for (long id : ids) {
                try {
                    tasks.deleteForever(userId, id);
                    deleted++;
                } catch (TaskNotFoundException e) {
                    // restored, deleted by a teammate, or access lost meanwhile
                }
            }
            afterId = ids.get(ids.size() - 1);
        }
    }

    /**
     * Deletes for good every task trashed more than {@link #RETENTION} ago, with the tag cleanup of delete forever.
     * Hourly (see {@link TrashPurgeScheduler}). Never throws: a task that fails is logged by the caller's next run.
     * Returns how many were deleted.
     */
    public int purgeExpired() {
        Instant cutoff = Instant.now(clock).minus(RETENTION).truncatedTo(ChronoUnit.MICROS);
        int deleted = 0;
        for (int batch = 0; batch < MAX_PURGE_BATCHES; batch++) {
            List<TrashQueries.Expired> expired = queries.findExpired(cutoff, BATCH);
            int progress = 0;
            for (TrashQueries.Expired e : expired) {
                if (tasks.purge(e.id(), e.listId(), cutoff)) {
                    progress++;
                }
            }
            deleted += progress;
            if (expired.size() < BATCH || progress == 0) {
                break;
            }
        }
        return deleted;
    }

    private Long scope(long userId, String listRaw) {
        return listRaw == null || listRaw.isEmpty() ? null : listService.resolve(userId, listRaw);
    }
}
