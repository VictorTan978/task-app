package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.TaskPage;
import com.taskapp.task.dto.TaskResponse;

class TaskServiceTest {

    private static final long OWNER = 7L;
    private static final Instant CREATED = Instant.parse("2026-10-01T18:00:00Z");
    /** Nanosecond precision on purpose: the service must truncate to what MySQL DATETIME(6) stores. */
    private static final Instant NOW = Instant.parse("2026-10-08T09:15:02.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-10-08T09:15:02.123456Z");

    private final TaskRepository repository = mock(TaskRepository.class);
    private final TagService tagService = mock(TagService.class);
    private final ListService listService = mock(ListService.class);
    private final com.taskapp.reminder.ReminderService reminders = mock(com.taskapp.reminder.ReminderService.class);
    private final RecurrenceService recurrence = mock(RecurrenceService.class);
    private TaskService service;

    /** The owner's "My tasks" (3) and another list of theirs (8). */
    private static final long MY_TASKS = 3L;
    private static final long HOME = 8L;
    private static final TaskList MY_TASKS_LIST = list(MY_TASKS, true);
    private static final TaskList HOME_LIST = list(HOME, false);

    private static final TaskResponse.AddedBy OWNER_NAME = new TaskResponse.AddedBy("Owner");

    private static TaskRepository.UserName userName(long id, String name) {
        return new TaskRepository.UserName() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getDisplayName() {
                return name;
            }
        };
    }

    private static TaskList list(long id, boolean personal) {
        TaskList list = new TaskList(OWNER, personal ? "My tasks" : "Home", personal, CREATED);
        ReflectionTestUtils.setField(list, "id", id);
        return list;
    }

    private TaskResponse replace(long userId, long id, ReplaceTaskRequest request) {
        return service.replace(userId, id, request).task();
    }

    private TaskResponse setStatus(long userId, long id, TaskStatus status, Boolean done) {
        return service.setStatus(userId, id, status, done).task();
    }

    @BeforeEach
    void setUp() {
        service = new TaskService(repository, tagService, listService, reminders, recurrence,
                TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(listService.resolve(OWNER, null)).thenReturn(MY_TASKS);
        when(listService.personal(OWNER)).thenReturn(MY_TASKS_LIST);
        when(listService.findMember(OWNER, MY_TASKS)).thenReturn(Optional.of(MY_TASKS_LIST));
        when(listService.findMember(OWNER, HOME)).thenReturn(Optional.of(HOME_LIST));
        when(listService.requireMember(OWNER, MY_TASKS)).thenReturn(MY_TASKS_LIST);
        when(listService.requireMember(OWNER, HOME)).thenReturn(HOME_LIST);
        when(listService.requireMember(OWNER, 99L)).thenThrow(new ListNotFoundException());
        when(listService.shareUsers(eq(OWNER), anyList())).thenReturn(true);
        when(listService.shareMember(anyLong(), eq(OWNER))).thenReturn(true);
        when(repository.findDisplayNames(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return ids.contains(OWNER) ? List.of(userName(OWNER, "Owner")) : List.of();
        });
        when(listService.lockList(anyLong())).thenReturn(true);
        when(listService.shareList(anyLong())).thenReturn(true);
        when(repository.saveAndFlush(any(Task.class))).thenAnswer(inv -> {
            Task task = inv.getArgument(0);
            if (task.getId() == null) {
                ReflectionTestUtils.setField(task, "id", 12L);
            }
            return task;
        });
        when(tagService.namesOf(anyLong(), anyLong())).thenReturn(List.of());
    }

    private static Task existing(long id) {
        Task task = new Task(OWNER, MY_TASKS, "Buy milk", "2%", LocalDate.parse("2026-10-10"), CREATED);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }

    private Task stored(long id, TaskStatus status, Priority priority) {
        Task task = existing(id);
        task.changeStatus(status, CREATED);
        task.changePriority(priority, CREATED);
        when(repository.findAccessible(id, OWNER)).thenReturn(Optional.of(task));
        when(repository.findByIdAndListId(id, MY_TASKS)).thenReturn(Optional.of(task));
        return task;
    }

    private static ReplaceTaskRequest put(Priority priority, TaskStatus status, Boolean done, List<String> tags) {
        return new ReplaceTaskRequest("Buy milk", "2%", LocalDate.parse("2026-10-10"), priority, status, done, tags,
                null);
    }

    private static ReplaceTaskRequest move(Long listId, List<String> tags) {
        return new ReplaceTaskRequest("Buy milk", "2%", LocalDate.parse("2026-10-10"), null, null, null, tags, listId);
    }

    @Test
    void createIsNoneTodoWithoutTagsAndHasEqualTimestamps() {
        TaskResponse created = service.create(OWNER,
                new CreateTaskRequest("Buy milk", null, LocalDate.parse("2026-10-10"), null));

        assertThat(created).isEqualTo(new TaskResponse(12L, MY_TASKS, OWNER_NAME, "Buy milk", null, Priority.NONE,
                TaskStatus.TODO, List.of(), false, LocalDate.parse("2026-10-10"), null, null, false, NOW_MICROS,
                NOW_MICROS));
        verify(repository).saveAndFlush(any(Task.class));
        verifyNoInteractions(tagService);
    }

    @Test
    void createStoresTheSessionOwnerInMyTasksAfterLockingUsersThenListThenMembership() {
        service.create(OWNER, new CreateTaskRequest("x", null, null, null));

        InOrder order = inOrder(listService, repository);
        order.verify(listService).personal(OWNER);
        order.verify(listService).shareUsers(OWNER, List.of(OWNER));
        order.verify(listService).shareList(MY_TASKS);
        order.verify(listService).shareMember(MY_TASKS, OWNER);
        order.verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                t -> t.getOwnerId() == OWNER && t.getListId() == MY_TASKS));
    }

    @Test
    void createGoesIntoTheGivenList() {
        TaskResponse created = service.create(OWNER, new CreateTaskRequest("x", null, null, HOME));

        assertThat(created.listId()).isEqualTo(HOME);
        verify(listService).shareList(HOME);
        verify(listService, never()).personal(anyLong());
    }

    @Test
    void createInAListThatIsNotMineOrWasJustDeletedIs404AndInsertsNothing() {
        assertThatThrownBy(() -> service.create(OWNER, new CreateTaskRequest("x", null, null, 99L)))
                .isInstanceOf(ListNotFoundException.class);
        when(listService.shareList(HOME)).thenReturn(false);
        assertThatThrownBy(() -> service.create(OWNER, new CreateTaskRequest("x", null, null, HOME)))
                .isInstanceOf(ListNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    private static List<Task> tasks(int count) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            tasks.add(existing(i));
        }
        return tasks;
    }

    private static TaskListQuery query(TaskFilter filter, TaskSort sort, String tag, String q, String cursor) {
        return TaskListQuery.of(null, filter.name().toLowerCase(), sort.name().toLowerCase(), tag, q, cursor);
    }

    private static TaskSearchCriteria criteria(TaskFilter filter, TaskSort sort, Long tagId, String search) {
        return new TaskSearchCriteria(MY_TASKS, filter, sort, tagId, search);
    }

    @Test
    void firstPageWithEverythingSkipsTheCountAndLoadsTagsOnce() {
        Task a = existing(1L);
        Task b = existing(2L);
        when(repository.findPage(criteria(TaskFilter.DONE, TaskSort.DUE, null, "milk"), null, 51))
                .thenReturn(List.of(a, b));
        when(tagService.namesByTask(MY_TASKS, List.of(1L, 2L))).thenReturn(Map.of(1L, List.of("Home", "Work")));

        TaskPage page = service.list(OWNER, query(TaskFilter.DONE, TaskSort.DUE, " ", "  milk ", ""));

        assertThat(page.items()).extracting(TaskResponse::tags).containsExactly(List.of("Home", "Work"), List.of());
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.nextCursor()).isNull();
        verify(repository, never()).countMatching(any());
        verify(tagService, times(1)).namesByTask(anyLong(), any());
        verify(tagService, never()).namesOf(anyLong(), anyLong());
        verify(tagService, never()).findTagId(anyLong(), any());
    }

    @Test
    void fullPageReturns50WithACursorAndTheCount() {
        List<Task> rows = tasks(51);
        TaskSearchCriteria criteria = criteria(TaskFilter.ALL, TaskSort.TITLE, null, null);
        when(repository.findPage(criteria, null, 51)).thenReturn(rows);
        when(repository.countMatching(criteria)).thenReturn(120L);
        when(tagService.namesByTask(anyLong(), any())).thenReturn(Map.of());

        TaskListQuery first = query(TaskFilter.ALL, TaskSort.TITLE, null, null, null);
        TaskPage page = service.list(OWNER, first);

        assertThat(page.items()).hasSize(50).extracting(TaskResponse::id).startsWith(1L).endsWith(50L);
        assertThat(page.total()).isEqualTo(120);
        assertThat(page.nextCursor()).matches("[A-Za-z0-9_-]+");
        assertThat(TaskCursor.decode(page.nextCursor(), TaskSort.TITLE, first.fingerprint(MY_TASKS)))
                .contains(TaskCursor.after(rows.get(49), TaskSort.TITLE));

        // The next page starts after that task and is always counted.
        TaskCursor after = TaskCursor.after(rows.get(49), TaskSort.TITLE);
        when(repository.findPage(criteria, after, 51)).thenReturn(List.of(existing(51)));
        TaskPage next = service.list(OWNER, query(TaskFilter.ALL, TaskSort.TITLE, null, null, page.nextCursor()));
        assertThat(next.items()).extracting(TaskResponse::id).containsExactly(51L);
        assertThat(next.total()).isEqualTo(120);
        assertThat(next.nextCursor()).isNull();
    }

    @Test
    void tagIsResolvedToTheOwnersTagId() {
        when(tagService.findTagId(MY_TASKS, " work ")).thenReturn(Optional.of(55L));
        when(repository.findPage(any(), any(), anyInt())).thenReturn(List.of());

        TaskPage page = service.list(OWNER, query(TaskFilter.ACTIVE, TaskSort.NEWEST, " work ", null, null));

        assertThat(page).isEqualTo(TaskPage.empty());
        verify(repository).findPage(criteria(TaskFilter.ACTIVE, TaskSort.NEWEST, 55L, null), null, 51);
    }

    @Test
    void unknownOrInvalidTagIsAnEmptyPage() {
        when(tagService.findTagId(anyLong(), any())).thenReturn(Optional.empty());

        assertThat(service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, "nope", null, null)))
                .isEqualTo(TaskPage.empty());
        assertThat(service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, "a,b", null, null)))
                .isEqualTo(TaskPage.empty());
        verify(repository, never()).findPage(any(), any(), anyInt());
        verify(repository, never()).countMatching(any());
    }

    @Test
    void searchOver100CharactersAfterTrimmingIs400() {
        String hundred = "é".repeat(100);
        when(repository.findPage(any(), any(), anyInt())).thenReturn(List.of());
        service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, null, "  " + hundred + "  ", null));

        assertThatThrownBy(() -> service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, null, hundred + "x", null)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.bindingResult().getFieldErrors()).hasSize(1);
                    assertThat(e.bindingResult().getFieldError("q").getCode()).isEqualTo("Size");
                });
        // UTF-16 code units, like JavaScript's length: 50 emoji = 100.
        service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, null, "😀".repeat(50), null));
        assertThatThrownBy(() -> service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, null, "😀".repeat(51), null)))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void cursorOfAnotherQueryOrGarbageIs400AndNothingIsRead() {
        Task last = existing(9L);
        TaskListQuery issuedFor = query(TaskFilter.ACTIVE, TaskSort.DUE, "Work", "milk", null);
        String cursor = TaskCursor.after(last, TaskSort.DUE).encode(TaskSort.DUE, issuedFor.fingerprint(MY_TASKS));
        when(tagService.findTagId(anyLong(), any())).thenReturn(Optional.of(55L));
        when(repository.findPage(any(), any(), anyInt())).thenReturn(List.of());

        // Same list after normalising: accepted.
        service.list(OWNER, query(TaskFilter.ACTIVE, TaskSort.DUE, " WÖRK ", " milk ", cursor));

        List<TaskListQuery> others = List.of(
                query(TaskFilter.ALL, TaskSort.DUE, "Work", "milk", cursor),
                query(TaskFilter.ACTIVE, TaskSort.NEWEST, "Work", "milk", cursor),
                query(TaskFilter.ACTIVE, TaskSort.DUE, "Home", "milk", cursor),
                query(TaskFilter.ACTIVE, TaskSort.DUE, null, "milk", cursor),
                query(TaskFilter.ACTIVE, TaskSort.DUE, "Work", "milky", cursor),
                query(TaskFilter.ACTIVE, TaskSort.DUE, "Work", "milk", cursor + "A"),
                query(TaskFilter.ACTIVE, TaskSort.DUE, "Work", "milk", "garbage"),
                query(TaskFilter.ACTIVE, TaskSort.DUE, "Work", "milk", "x".repeat(1025)));
        for (TaskListQuery other : others) {
            assertThatThrownBy(() -> service.list(OWNER, other)).as(other.toString())
                    .isInstanceOfSatisfying(InvalidFieldsException.class, e ->
                            assertThat(e.bindingResult().getFieldError("cursor").getCode())
                                    .isEqualTo(TaskService.INVALID_CURSOR_CONSTRAINT));
        }
        verify(repository, times(1)).findPage(any(), any(), anyInt());
    }

    @Test
    void badCursorAndLongSearchAreReportedTogether() {
        assertThatThrownBy(() -> service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, null, "x".repeat(101), "@")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e ->
                        assertThat(e.bindingResult().getFieldErrors()).extracting(f -> f.getField())
                                .containsExactlyInAnyOrder("cursor", "q"));
        verifyNoInteractions(repository, tagService);
    }

    /** AC15: a cursor issued in one list is INVALID_CURSOR in another, with otherwise identical parameters. */
    @Test
    void cursorOfAnotherListIs400() {
        when(listService.resolve(OWNER, "8")).thenReturn(HOME);
        String cursor = TaskCursor.after(existing(9L), TaskSort.NEWEST)
                .encode(TaskSort.NEWEST, query(TaskFilter.ALL, TaskSort.NEWEST, null, null, null).fingerprint(MY_TASKS));
        when(repository.findPage(any(), any(), anyInt())).thenReturn(List.of());

        service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, null, null, cursor));
        assertThatThrownBy(() -> service.list(OWNER, TaskListQuery.of("8", "all", "newest", null, null, cursor)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e ->
                        assertThat(e.bindingResult().getFieldError("cursor").getCode())
                                .isEqualTo(TaskService.INVALID_CURSOR_CONSTRAINT));
    }

    /** The list is checked first: a 404 wins over a bad cursor and a long search, and nothing else is read. */
    @Test
    void listNotMineIs404BeforeAnythingElse() {
        when(listService.resolve(OWNER, "99")).thenThrow(new ListNotFoundException());

        assertThatThrownBy(() -> service.list(OWNER, TaskListQuery.of("99", null, null, "Work", "x".repeat(101), "@")))
                .isInstanceOf(ListNotFoundException.class);
        verifyNoInteractions(repository, tagService);
    }

    @Test
    void listQueriesTheResolvedList() {
        when(listService.resolve(OWNER, "8")).thenReturn(HOME);
        when(tagService.findTagId(HOME, "Work")).thenReturn(Optional.of(5L));
        when(repository.findPage(any(), any(), anyInt())).thenReturn(List.of());

        service.list(OWNER, TaskListQuery.of("8", null, null, "Work", null, null));

        verify(repository).findPage(new TaskSearchCriteria(HOME, TaskFilter.ALL, TaskSort.NEWEST, 5L, null), null, 51);
    }

    @Test
    void getIncludesTheTasksTags() {
        stored(12L, TaskStatus.IN_PROGRESS, Priority.LOW);
        when(tagService.namesOf(MY_TASKS, 12L)).thenReturn(List.of("Errands"));

        TaskResponse task = service.get(OWNER, 12L);

        assertThat(task.tags()).containsExactly("Errands");
        assertThat(task.priority()).isEqualTo(Priority.LOW);
        assertThat(task.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.done()).isFalse();
    }

    @Test
    void replaceOverwritesTextFieldsAndClearsNulls() {
        stored(12L, TaskStatus.TODO, Priority.NONE);

        TaskResponse updated = replace(OWNER, 12L,
                new ReplaceTaskRequest("Buy oat milk", null, null, null, null, true, null, null));

        assertThat(updated).isEqualTo(new TaskResponse(12L, MY_TASKS, OWNER_NAME, "Buy oat milk", null, Priority.NONE,
                TaskStatus.DONE,
                List.of(), true, null, null, null, false, CREATED, NOW_MICROS));
    }

    // ---- 0.14.0: due time and reminder ----

    private static ReplaceTaskRequest due(String date, com.taskapp.task.dto.DueTimeUpdate time, String reminder) {
        return new ReplaceTaskRequest("Buy milk", "2%", date == null ? null : LocalDate.parse(date), time, null, null,
                null, null, null, reminder);
    }

    private static com.taskapp.task.dto.DueTimeUpdate at(int hour, int minute) {
        return new com.taskapp.task.dto.DueTimeUpdate(java.time.LocalTime.of(hour, minute));
    }

    @Test
    void replaceSetsTheDueTimeAndHandsTheReminderRequestToTheReminderService() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);

        replace(OWNER, 12L, due("2026-10-21", at(9, 30), "ONE_HOUR"));

        assertThat(task.getDueTime()).isEqualTo(java.time.LocalTime.of(9, 30));
        verify(reminders).afterTaskSaved(task, OWNER, "ONE_HOUR");
    }

    @Test
    void aDueTimeThatIsNotSentStaysAndANullClearsIt() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);
        replace(OWNER, 12L, due("2026-10-21", at(9, 30), null));

        replace(OWNER, 12L, due("2026-10-22", null, null));
        assertThat(task.getDueDate()).isEqualTo(LocalDate.parse("2026-10-22"));
        assertThat(task.getDueTime()).isEqualTo(java.time.LocalTime.of(9, 30));

        replace(OWNER, 12L, due("2026-10-22", com.taskapp.task.dto.DueTimeUpdate.CLEAR, null));
        assertThat(task.getDueTime()).isNull();
        assertThat(task.getDueDate()).isEqualTo(LocalDate.parse("2026-10-22"));
    }

    @Test
    void clearingTheDateDropsTheStoredTime() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);
        replace(OWNER, 12L, due("2026-10-21", at(9, 30), null));

        replace(OWNER, 12L, due(null, null, null));

        assertThat(task.getDueDate()).isNull();
        assertThat(task.getDueTime()).isNull();
        verify(reminders, org.mockito.Mockito.times(2)).afterTaskSaved(task, OWNER, null);
    }

    @Test
    void anOffsetThatDoesNotFitTheTaskIsInvalidReminderAndNothingIsSavedOrScheduled() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);

        // Timed with a date-only offset, date-only with a timed offset.
        assertInvalidReminder(due("2026-10-21", at(9, 30), "ON_THE_DAY"));
        assertInvalidReminder(due("2026-10-21", null, "AT_DUE"));
        assertInvalidReminder(due("2026-10-21", com.taskapp.task.dto.DueTimeUpdate.CLEAR, "TEN_MINUTES"));

        verify(reminders, never()).afterTaskSaved(any(), anyLong(), any());
        assertThat(task.getDueTime()).isNull();
    }

    @Test
    void aStoredTimeMakesATimedOffsetFitWhenTheRequestDoesNotSendOne() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);
        replace(OWNER, 12L, due("2026-10-21", at(9, 30), null));

        replace(OWNER, 12L, due("2026-10-22", null, "AT_DUE"));

        verify(reminders).afterTaskSaved(task, OWNER, "AT_DUE");
        assertInvalidReminder(due("2026-10-22", null, "ON_THE_DAY"));
    }

    @Test
    void noneAndAMissingReminderNeverFailTheFitCheck() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);
        replace(OWNER, 12L, due("2026-10-21", null, "NONE"));
        replace(OWNER, 12L, due(null, null, "NONE"));
        verify(reminders, org.mockito.Mockito.times(2)).afterTaskSaved(task, OWNER, "NONE");
    }

    @Test
    void theResponseCarriesMyReminder() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(reminders.mine(OWNER, List.of(12L))).thenReturn(java.util.Map.of(12L,
                new com.taskapp.reminder.ReminderService.Mine(com.taskapp.reminder.ReminderOffset.ONE_HOUR, true)));

        TaskResponse response = service.get(OWNER, 12L);

        assertThat(response.myReminder()).isEqualTo(com.taskapp.reminder.ReminderOffset.ONE_HOUR);
        assertThat(response.myReminderPassed()).isTrue();
    }

    private void assertInvalidReminder(ReplaceTaskRequest request) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> replace(OWNER, 12L, request))
                .isInstanceOfSatisfying(com.taskapp.auth.InvalidFieldsException.class, e -> {
                    assertThat(e.bindingResult().getFieldErrors()).hasSize(1);
                    assertThat(e.bindingResult().getFieldError().getField()).isEqualTo("reminder");
                    assertThat(e.bindingResult().getFieldError().getCode()).isEqualTo("InvalidReminder");
                });
    }

    @Test
    void replaceWithoutNewFieldsLeavesPriorityStatusAndTagsUnchanged() {
        stored(12L, TaskStatus.IN_PROGRESS, Priority.HIGH);
        when(tagService.namesOf(MY_TASKS, 12L)).thenReturn(List.of("Home"));

        TaskResponse updated = replace(OWNER, 12L, put(null, null, null, null));

        assertThat(updated.priority()).isEqualTo(Priority.HIGH);
        assertThat(updated.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(updated.done()).isFalse();
        assertThat(updated.tags()).containsExactly("Home");
        assertThat(updated.updatedAt()).isEqualTo(NOW_MICROS);
        verify(tagService, never()).replaceTags(anyLong(), anyLong(), anyLong(), any());
        // PUT without tags or listId: the list FOR SHARE only (never waits for another field-only write).
        InOrder order = inOrder(listService, repository);
        order.verify(listService).shareUsers(eq(OWNER), anyList());
        order.verify(listService).shareList(MY_TASKS);
        order.verify(repository).findByIdAndListId(12L, MY_TASKS);
        verify(listService, never()).lockList(anyLong());
    }

    @Test
    void replaceSetsPriority() {
        Task task = stored(12L, TaskStatus.TODO, Priority.HIGH);

        assertThat(replace(OWNER, 12L, put(Priority.NONE, null, null, null)).priority()).isEqualTo(Priority.NONE);
        assertThat(task.getPriority()).isEqualTo(Priority.NONE);
        assertThat(replace(OWNER, 12L, put(Priority.MEDIUM, null, null, null)).priority())
                .isEqualTo(Priority.MEDIUM);
    }

    /** Every row of the contract's PUT table (both sent and disagreeing is rejected by validation). */
    @Test
    void replaceStatusAndDoneMatrix() {
        record Row(TaskStatus from, TaskStatus status, Boolean done, TaskStatus expected) {
        }
        List<Row> rows = List.of(
                new Row(TaskStatus.TODO, null, null, TaskStatus.TODO),
                new Row(TaskStatus.IN_PROGRESS, null, null, TaskStatus.IN_PROGRESS),
                new Row(TaskStatus.DONE, null, null, TaskStatus.DONE),
                new Row(TaskStatus.TODO, TaskStatus.IN_PROGRESS, null, TaskStatus.IN_PROGRESS),
                new Row(TaskStatus.DONE, TaskStatus.IN_PROGRESS, null, TaskStatus.IN_PROGRESS),
                new Row(TaskStatus.DONE, TaskStatus.TODO, null, TaskStatus.TODO),
                new Row(TaskStatus.IN_PROGRESS, TaskStatus.DONE, null, TaskStatus.DONE),
                new Row(TaskStatus.TODO, null, true, TaskStatus.DONE),
                new Row(TaskStatus.IN_PROGRESS, null, true, TaskStatus.DONE),
                new Row(TaskStatus.DONE, null, true, TaskStatus.DONE),
                new Row(TaskStatus.DONE, null, false, TaskStatus.TODO),
                new Row(TaskStatus.TODO, null, false, TaskStatus.TODO),
                new Row(TaskStatus.IN_PROGRESS, null, false, TaskStatus.IN_PROGRESS),
                new Row(TaskStatus.TODO, TaskStatus.DONE, true, TaskStatus.DONE),
                new Row(TaskStatus.DONE, TaskStatus.IN_PROGRESS, false, TaskStatus.IN_PROGRESS),
                new Row(TaskStatus.IN_PROGRESS, TaskStatus.TODO, false, TaskStatus.TODO));
        for (Row row : rows) {
            Task task = stored(12L, row.from(), Priority.NONE);
            TaskResponse put = replace(OWNER, 12L, put(null, row.status(), row.done(), null));
            assertThat(put.status()).as("PUT %s", row).isEqualTo(row.expected());
            assertThat(put.done()).as("PUT %s", row).isEqualTo(row.expected() == TaskStatus.DONE);
            assertThat(task.isDone()).isEqualTo(task.getStatus() == TaskStatus.DONE);

            stored(12L, row.from(), Priority.NONE);
            TaskResponse patch = setStatus(OWNER, 12L, row.status(), row.done());
            if (row.status() != null || row.done() != null) {
                assertThat(patch.status()).as("PATCH %s", row).isEqualTo(row.expected());
                assertThat(patch.done()).as("PATCH %s", row).isEqualTo(row.expected() == TaskStatus.DONE);
                assertThat(patch.updatedAt()).isEqualTo(NOW_MICROS);
            }
        }
    }

    @Test
    void replaceWithTagsLocksUserThenListThenRereadsTheTaskInItsList() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(tagService.replaceTags(OWNER, MY_TASKS, 12L, List.of(" Home ", "work")))
                .thenReturn(List.of("Home", "Work"));

        TaskResponse updated = replace(OWNER, 12L, put(null, null, null, List.of(" Home ", "work")));

        assertThat(updated.tags()).containsExactly("Home", "Work");
        InOrder order = inOrder(listService, tagService, repository);
        order.verify(repository).findAccessible(12L, OWNER);
        order.verify(listService).shareUsers(eq(OWNER), anyList());
        order.verify(listService).lockList(MY_TASKS);
        order.verify(repository).findByIdAndListId(12L, MY_TASKS);
        order.verify(repository).saveAndFlush(any(Task.class));
        order.verify(tagService).replaceTags(OWNER, MY_TASKS, 12L, List.of(" Home ", "work"));
    }

    @Test
    void replaceWithEmptyTagsClearsThem() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(tagService.replaceTags(OWNER, MY_TASKS, 12L, List.of())).thenReturn(List.of());

        assertThat(replace(OWNER, 12L, put(null, null, null, List.of())).tags()).isEmpty();
        verify(tagService).replaceTags(OWNER, MY_TASKS, 12L, List.of());
    }

    @Test
    void patchSharesTheListAndRestartsWhenTheTaskMovedMeanwhile() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.findByIdAndListId(12L, MY_TASKS)).thenReturn(Optional.empty(), Optional.of(task));

        assertThat(setStatus(OWNER, 12L, TaskStatus.DONE, null).status()).isEqualTo(TaskStatus.DONE);

        verify(listService, times(2)).shareList(MY_TASKS);
        verify(listService, never()).lockList(anyLong());
    }

    @Test
    void setStatusNeverTouchesTagsOrPriority() {
        stored(12L, TaskStatus.TODO, Priority.HIGH);
        when(tagService.namesOf(MY_TASKS, 12L)).thenReturn(List.of("Work"));

        TaskResponse patched = setStatus(OWNER, 12L, TaskStatus.IN_PROGRESS, null);

        assertThat(patched.priority()).isEqualTo(Priority.HIGH);
        assertThat(patched.tags()).containsExactly("Work");
        assertThat(patched.title()).isEqualTo("Buy milk");
        assertThat(patched.description()).isEqualTo("2%");
        verify(listService, never()).lockList(anyLong());
        verify(tagService, never()).replaceTags(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void deleteMovesTheTaskToTheTrashAndKeepsItsTags() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.trash(eq(12L), eq(OWNER), any(Instant.class))).thenReturn(1);

        service.delete(OWNER, 12L);

        verify(repository).trash(12L, OWNER, NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        verify(repository, never()).delete(any());
        verify(tagService, never()).deleteUnused(anyLong(), any());
        verify(listService, never()).lockList(anyLong());
    }

    @Test
    void deleteForeverLocksRemovesTheTrashedTaskAndThenUnusedTags() {
        Task task = existing(12L);
        when(repository.findTrashedAccessible(12L, OWNER)).thenReturn(Optional.of(task));
        when(repository.findTrashedByIdAndListId(12L, MY_TASKS)).thenReturn(Optional.of(task));
        when(tagService.tagIdsOf(12L)).thenReturn(List.of(5L, 7L));

        service.deleteForever(OWNER, 12L);

        InOrder order = inOrder(listService, tagService, repository);
        order.verify(listService).shareUsers(eq(OWNER), anyList());
        order.verify(listService).lockList(MY_TASKS);
        order.verify(tagService).tagIdsOf(12L);
        order.verify(repository).delete(task);
        order.verify(repository).flush();
        order.verify(tagService).deleteUnused(MY_TASKS, List.of(5L, 7L));
    }

    @Test
    void deleteForeverOfALiveTaskIsNotFound() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.findTrashedAccessible(12L, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteForever(OWNER, 12L)).isInstanceOf(TaskNotFoundException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void missingOrForeignTaskThrowsNotFoundAndChangesNothing() {
        when(repository.findAccessible(42L, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(OWNER, 42L)).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> replace(OWNER, 42L, put(Priority.HIGH, null, false, List.of("x"))))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> setStatus(OWNER, 42L, null, true)).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.delete(OWNER, 42L)).isInstanceOf(TaskNotFoundException.class);

        assertThatThrownBy(() -> replace(OWNER, 42L, move(HOME, null))).isInstanceOf(TaskNotFoundException.class);

        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).delete(any());
        verify(tagService, never()).replaceTags(anyLong(), anyLong(), anyLong(), any());
        verify(tagService, never()).deleteUnused(anyLong(), any());
    }

    /**
     * Reproduces the race where a concurrent DELETE commits between {@code find} and the flush: Hibernate's update
     * then matches zero rows and throws this (there is no {@code @Version} column), which must become a 404, not a
     * 500.
     */
    @Test
    void patchIsNotFoundWhenTheTaskWasDeletedJustBeforeTheFlush() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.saveAndFlush(any(Task.class))).thenThrow(new ObjectOptimisticLockingFailureException(Task.class, 12L));

        assertThatThrownBy(() -> setStatus(OWNER, 12L, TaskStatus.DONE, null))
                .isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(tagService);
    }

    @Test
    void putIsNotFoundWhenTheTaskWasDeletedJustBeforeTheFlush() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.saveAndFlush(any(Task.class))).thenThrow(new ObjectOptimisticLockingFailureException(Task.class, 12L));

        assertThatThrownBy(() -> replace(OWNER, 12L, put(null, null, null, null)))
                .isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(tagService);
    }

    @Test
    void putWithTagsIsNotFoundWhenTheTaskWasDeletedJustBeforeTheFlushAndNeverTouchesTags() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.saveAndFlush(any(Task.class))).thenThrow(new ObjectOptimisticLockingFailureException(Task.class, 12L));

        assertThatThrownBy(() -> replace(OWNER, 12L, put(null, null, null, List.of("Work"))))
                .isInstanceOf(TaskNotFoundException.class);
        verify(listService).lockList(MY_TASKS);
        verify(tagService, never()).replaceTags(anyLong(), anyLong(), anyLong(), any());
    }

    private static CannotAcquireLockException lockFailure(String sqlState, int errorCode) {
        return new CannotAcquireLockException("lock", new SQLException("lock", sqlState, errorCode));
    }

    @Test
    void replaceRunsTheWholeTransactionAgainAfterLosingADeadlock() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.findByIdAndListId(12L, MY_TASKS)).thenThrow(lockFailure("40001", 1213))
                .thenReturn(Optional.of(task));
        when(tagService.replaceTags(OWNER, MY_TASKS, 12L, List.of("Work"))).thenReturn(List.of("Work"));

        assertThat(replace(OWNER, 12L, put(null, null, null, List.of("Work"))).tags()).containsExactly("Work");

        verify(listService, times(2)).lockList(MY_TASKS);
        verify(tagService, times(1)).replaceTags(OWNER, MY_TASKS, 12L, List.of("Work"));
    }

    @Test
    void deleteRetriesADeadlockAtMostThreeTimesInAll() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.findByIdAndListId(12L, MY_TASKS)).thenThrow(lockFailure("40001", 1213));

        assertThatThrownBy(() -> service.delete(OWNER, 12L)).isInstanceOf(CannotAcquireLockException.class);

        verify(repository, times(DeadlockRetry.MAX_ATTEMPTS)).findByIdAndListId(12L, MY_TASKS);
    }

    @Test
    void aLockWaitTimeoutIsNotRetried() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.findByIdAndListId(12L, MY_TASKS)).thenThrow(lockFailure("HY000", 1205));

        assertThatThrownBy(() -> replace(OWNER, 12L, put(null, null, null, List.of("Work"))))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThatThrownBy(() -> service.delete(OWNER, 12L)).isInstanceOf(CannotAcquireLockException.class);

        verify(repository, times(2)).findByIdAndListId(12L, MY_TASKS);
    }

    // ---- moves (0.12.0, AC10/AC11/AC13) ----

    @Test
    void moveLocksBothListsLowestIdFirstThenMovesAndMovesTheTags() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.moveToList(12L, MY_TASKS, HOME)).thenReturn(1);
        when(tagService.moveTags(OWNER, MY_TASKS, HOME, 12L, null)).thenReturn(List.of("Work"));

        TaskResponse moved = replace(OWNER, 12L, move(HOME, null));

        assertThat(moved.listId()).isEqualTo(HOME);
        assertThat(moved.tags()).containsExactly("Work");
        assertThat(moved.createdAt()).isEqualTo(CREATED);
        assertThat(moved.updatedAt()).isEqualTo(NOW_MICROS);
        assertThat(task.getListId()).isEqualTo(HOME);
        InOrder order = inOrder(listService, tagService, repository);
        order.verify(listService).shareUsers(eq(OWNER), anyList());
        order.verify(listService).lockList(MY_TASKS);
        order.verify(listService).lockList(HOME);
        order.verify(repository).findByIdAndListId(12L, MY_TASKS);
        order.verify(repository).saveAndFlush(task);
        order.verify(repository).moveToList(12L, MY_TASKS, HOME);
        order.verify(tagService).moveTags(OWNER, MY_TASKS, HOME, 12L, null);
        verify(tagService, never()).replaceTags(anyLong(), anyLong(), anyLong(), any());
    }

    /** A→B and B→A take the same lock order (lowest id first), so they can't deadlock on the list rows. */
    @Test
    void moveFromTheHigherIdListStillLocksTheLowerIdFirst() {
        Task task = new Task(OWNER, HOME, "x", null, null, CREATED);
        ReflectionTestUtils.setField(task, "id", 12L);
        when(repository.findAccessible(12L, OWNER)).thenReturn(Optional.of(task));
        when(repository.findByIdAndListId(12L, HOME)).thenReturn(Optional.of(task));
        when(repository.moveToList(12L, HOME, MY_TASKS)).thenReturn(1);
        when(tagService.moveTags(OWNER, HOME, MY_TASKS, 12L, List.of("A"))).thenReturn(List.of("A"));

        assertThat(replace(OWNER, 12L, move(MY_TASKS, List.of("A"))).listId()).isEqualTo(MY_TASKS);

        InOrder order = inOrder(listService);
        order.verify(listService).lockList(MY_TASKS);
        order.verify(listService).lockList(HOME);
    }

    @Test
    void theTasksCurrentListIsNotAMove() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(tagService.replaceTags(OWNER, MY_TASKS, 12L, List.of("A"))).thenReturn(List.of("A"));

        assertThat(replace(OWNER, 12L, move(MY_TASKS, null)).listId()).isEqualTo(MY_TASKS);
        assertThat(replace(OWNER, 12L, move(MY_TASKS, List.of("A"))).tags()).containsExactly("A");

        verify(repository, never()).moveToList(anyLong(), anyLong(), anyLong());
        verify(tagService, never()).moveTags(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(listService, never()).lockList(HOME);
    }

    @Test
    void moveToAListThatIsNotMineIs404ListNotFoundAndChangesNothing() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);

        assertThatThrownBy(() -> replace(OWNER, 12L, move(99L, null)))
                .isInstanceOf(ListNotFoundException.class);

        assertThat(task.getUpdatedAt()).isEqualTo(CREATED);
        verify(listService, never()).lockList(anyLong());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void moveToATargetDeletedMeanwhileIs404ListNotFoundBeforeAnyWrite() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(listService.lockList(HOME)).thenReturn(false);

        assertThatThrownBy(() -> replace(OWNER, 12L, move(HOME, null)))
                .isInstanceOf(ListNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).moveToList(anyLong(), anyLong(), anyLong());
    }

    /** The task is checked first: gone (its list deleted, or deleted itself) is NOT_FOUND even with a bad target. */
    @Test
    void taskGoneIsNotFoundBeforeTheTargetIsChecked() {
        when(repository.findAccessible(12L, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> replace(OWNER, 12L, move(99L, null)))
                .isInstanceOf(TaskNotFoundException.class);
        verify(listService, never()).requireMember(anyLong(), anyLong());
    }

    @Test
    void aTaskWhoseListIsDeletedBetweenTheReadsIsNotFound() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(listService.findMember(OWNER, MY_TASKS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> replace(OWNER, 12L, put(null, null, null, List.of("A"))))
                .isInstanceOf(TaskNotFoundException.class);
    }

    /** It moved to another list between the short read and the locks: start again and work in its new list. */
    @Test
    void aTaskMovedMeanwhileIsHandledInItsNewList() {
        Task inMyTasks = existing(12L);
        Task inHome = new Task(OWNER, HOME, "Buy milk", "2%", null, CREATED);
        ReflectionTestUtils.setField(inHome, "id", 12L);
        when(repository.findAccessible(12L, OWNER)).thenReturn(Optional.of(inMyTasks), Optional.of(inHome));
        when(repository.findByIdAndListId(12L, MY_TASKS)).thenReturn(Optional.empty());
        when(repository.findByIdAndListId(12L, HOME)).thenReturn(Optional.of(inHome));
        when(tagService.replaceTags(OWNER, HOME, 12L, List.of("A"))).thenReturn(List.of("A"));

        TaskResponse saved = replace(OWNER, 12L, put(null, null, null, List.of("A")));

        assertThat(saved.listId()).isEqualTo(HOME);
        verify(tagService).replaceTags(OWNER, HOME, 12L, List.of("A"));
        verify(tagService, never()).replaceTags(anyLong(), org.mockito.ArgumentMatchers.eq(MY_TASKS), anyLong(), any());
    }

    @Test
    void aTaskThatKeepsMovingAwayIsNotFoundAfterThreeAttempts() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.findByIdAndListId(12L, MY_TASKS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(OWNER, 12L)).isInstanceOf(TaskNotFoundException.class);

        verify(repository, times(TaskService.MAX_LOCATE_ATTEMPTS)).findAccessible(12L, OWNER);
        verify(repository, never()).delete(any());
    }

    @Test
    void anAccountDeletedMeanwhileIsNotFound() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(listService.shareUsers(eq(OWNER), anyList())).thenReturn(false);

        assertThatThrownBy(() -> service.delete(OWNER, 12L)).isInstanceOf(TaskNotFoundException.class);
        verify(listService, never()).lockList(anyLong());
    }

    /** AC11: the tag limit is thrown inside the transaction (rolled back by the caller's template). */
    @Test
    void moveOverTheTargetsTagLimitPropagates() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.moveToList(12L, MY_TASKS, HOME)).thenReturn(1);
        when(tagService.moveTags(OWNER, MY_TASKS, HOME, 12L, null)).thenThrow(new TagLimitExceededException());

        assertThatThrownBy(() -> replace(OWNER, 12L, move(HOME, null)))
                .isInstanceOf(TagLimitExceededException.class);
    }

    @Test
    void newTagsBelongToTheListsOwner() {
        TaskList othersList = new TaskList(42L, "Shared", false, CREATED);
        ReflectionTestUtils.setField(othersList, "id", MY_TASKS);
        when(listService.findMember(OWNER, MY_TASKS)).thenReturn(Optional.of(othersList));
        stored(12L, TaskStatus.TODO, Priority.NONE);

        replace(OWNER, 12L, put(null, null, null, List.of("A")));

        verify(tagService).replaceTags(42L, MY_TASKS, 12L, List.of("A"));
    }

    // ---- 0.13.0: shared lists ----

    /** A member removed (or who left) between the read and the lock: the membership re-check fails, then 404. */
    @Test
    void aMemberRemovedMeanwhileIsNotFoundAndNothingIsWritten() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(listService.shareMember(MY_TASKS, OWNER)).thenReturn(false);

        assertThatThrownBy(() -> setStatus(OWNER, 12L, TaskStatus.DONE, null))
                .isInstanceOf(TaskNotFoundException.class);
        verify(listService, times(TaskService.MAX_LOCATE_ATTEMPTS)).shareMember(MY_TASKS, OWNER);
        verify(repository, never()).findByIdAndListId(anyLong(), anyLong());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aMoveTargetNoLongerMineIsListNotFound() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(listService.shareMember(HOME, OWNER)).thenReturn(false);

        assertThatThrownBy(() -> replace(OWNER, 12L, move(HOME, null)))
                .isInstanceOf(ListNotFoundException.class);
        verify(repository, never()).moveToList(anyLong(), anyLong(), anyLong());
    }

    /** A member's write in someone else's list: the caller's and the owner's users rows are locked first. */
    @Test
    void aWriteInASharedListLocksTheOwnersUserRowToo() {
        TaskList shared = new TaskList(42L, "Shared", false, CREATED);
        ReflectionTestUtils.setField(shared, "id", MY_TASKS);
        when(listService.findMember(OWNER, MY_TASKS)).thenReturn(Optional.of(shared));
        stored(12L, TaskStatus.TODO, Priority.NONE);

        replace(OWNER, 12L, put(null, null, null, List.of("A")));

        InOrder order = inOrder(listService, tagService);
        order.verify(listService).shareUsers(OWNER, List.of(42L));
        order.verify(listService).lockList(MY_TASKS);
        order.verify(listService).shareMember(MY_TASKS, OWNER);
        order.verify(tagService).replaceTags(42L, MY_TASKS, 12L, List.of("A"));
    }

    @Test
    void addedByIsTheAddersNameAndNullOnceTheirAccountIsGone() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        assertThat(service.get(OWNER, 12L).addedBy()).isEqualTo(OWNER_NAME);

        Task orphan = existing(13L);
        ReflectionTestUtils.setField(orphan, "ownerId", null);
        when(repository.findAccessible(13L, OWNER)).thenReturn(Optional.of(orphan));
        assertThat(service.get(OWNER, 13L).addedBy()).isNull();
    }

    // ---- T4b: recurring tasks ----

    private static final LocalDate DUE = LocalDate.parse("2026-10-10");

    private Task repeating(RepeatRule rule, LocalDate anchor) {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);
        task.setRepeat(rule, anchor);
        Task next = existing(99L);
        when(recurrence.createNext(eq(OWNER), eq(task), any(), any(), any())).thenReturn(next);
        return task;
    }

    private static ReplaceTaskRequest repeatPut(TaskStatus status, com.taskapp.task.dto.RepeatInput repeat) {
        return new ReplaceTaskRequest("Buy milk", "2%", DUE, null, null, status, null, null, null, null, repeat);
    }

    private static com.taskapp.task.dto.RepeatInput rule(String frequency) {
        return com.taskapp.task.dto.RepeatInput.ofObject(frequency, null);
    }

    @Test
    void completingARepeatingTaskCreatesTheNextOneAndTheRuleIsAlreadyOnTheCopyWhenTheOriginalIsSaved() {
        RepeatRule daily = new RepeatRule(RepeatFrequency.DAILY, 0);
        Task task = repeating(daily, DUE);
        List<RepeatRule> ruleWhenSaved = new ArrayList<>();
        when(repository.saveAndFlush(any(Task.class))).thenAnswer(inv -> {
            Task saved = inv.getArgument(0);
            ruleWhenSaved.add(saved.getRepeat());
            return saved;
        });

        var result = service.setStatus(OWNER, 12L, null, true);

        assertThat(result.task().repeat()).isNull();
        assertThat(result.nextTask().id()).isEqualTo(99L);
        verify(recurrence, times(1)).createNext(eq(OWNER), eq(task), eq(daily), eq(DUE), any());
        assertThat(ruleWhenSaved).containsExactly((RepeatRule) null);
    }

    @Test
    void doneToDoneAndNeverCreateNoCopy() {
        Task task = repeating(new RepeatRule(RepeatFrequency.DAILY, 0), DUE);
        task.changeStatus(TaskStatus.DONE, CREATED);
        assertThat(service.setStatus(OWNER, 12L, null, true).nextTask()).isNull();
        assertThat(service.replace(OWNER, 12L, repeatPut(TaskStatus.DONE, null)).nextTask()).isNull();

        Task other = repeating(new RepeatRule(RepeatFrequency.DAILY, 0), DUE);
        var never = service.replace(OWNER, 12L, repeatPut(TaskStatus.DONE, com.taskapp.task.dto.RepeatInput.ofText("NEVER")));
        assertThat(never.nextTask()).isNull();
        assertThat(never.task().repeat()).isNull();
        verify(recurrence, never()).createNext(anyLong(), any(), any(), any(), any());
        assertThat(other.getRepeat()).isNull();
    }

    @Test
    void theAnchorRestartsOnlyWhenTheRuleOrTheDueDateChanges() {
        LocalDate anchor = LocalDate.parse("2026-01-31");
        Task task = repeating(new RepeatRule(RepeatFrequency.MONTHLY, 0), anchor);
        // Same rule, same date re-sent: the anchor stays.
        service.replace(OWNER, 12L, repeatPut(null, rule("MONTHLY")));
        assertThat(task.getRepeatAnchor()).isEqualTo(anchor);
        // Nothing sent: unchanged.
        service.replace(OWNER, 12L, repeatPut(null, null));
        assertThat(task.getRepeatAnchor()).isEqualTo(anchor);
        // Another rule on the same date: the anchor is the due date.
        service.replace(OWNER, 12L, repeatPut(null, rule("YEARLY")));
        assertThat(task.getRepeat().frequency()).isEqualTo(RepeatFrequency.YEARLY);
        assertThat(task.getRepeatAnchor()).isEqualTo(DUE);
        // Another due date with the rule unsent: the anchor follows the date.
        service.replace(OWNER, 12L, new ReplaceTaskRequest("Buy milk", "2%", LocalDate.parse("2026-11-02"), null, null,
                null, null, null));
        assertThat(task.getRepeatAnchor()).isEqualTo(LocalDate.parse("2026-11-02"));
    }

    @Test
    void clearingTheDueDateClearsTheRule() {
        Task task = repeating(new RepeatRule(RepeatFrequency.DAILY, 0), DUE);
        service.replace(OWNER, 12L, new ReplaceTaskRequest("Buy milk", "2%", null, null, null, null, null, null));
        assertThat(task.getRepeat()).isNull();
        assertThat(task.getRepeatAnchor()).isNull();
    }
}
