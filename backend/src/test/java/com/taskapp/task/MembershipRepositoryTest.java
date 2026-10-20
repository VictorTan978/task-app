package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.auth.User;
import com.taskapp.task.MembershipRepository.MemberRow;
import com.taskapp.task.MembershipRepository.MyInvitationRow;

/** V11 ({@code list_members}, {@code list_invitations}, {@code tasks.owner_id}) and the custom queries, MySQL 8.4. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ListService.class, MembershipRepository.class})
class MembershipRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    MembershipRepository members;

    @Autowired
    ListRepository lists;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TestEntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    long ana;
    long bob;
    long zoe;
    long home;

    private long user(String email, String name) {
        return em.persistAndFlush(new User(email, "$2a$10$" + "x".repeat(53), name)).getId();
    }

    private void verify(long userId) {
        jdbc.update("update users set email_verified_at = ? where id = ?", Timestamp.from(NOW), userId);
    }

    private long invite(long listId, String email, Instant invitedAt, Duration lifetime) {
        return members.insertInvitation(listId, email, invitedAt, invitedAt.plus(lifetime));
    }

    @BeforeEach
    void data() {
        ana = user("ana@example.com", "Ana");
        bob = user("bob@example.com", "bob");
        zoe = user("zoe@example.com", "Zoë");
        home = TestLists.other(jdbc, ana, "Home");
    }

    @Test
    void membersAreOwnerFirstThenByNameIgnoringCaseAndAccentsThenId() {
        TestLists.member(jdbc, home, zoe);
        TestLists.member(jdbc, home, bob);

        assertThat(members.members(home)).extracting(MemberRow::displayName, MemberRow::owner)
                .containsExactly(tuple("Ana", true), tuple("bob", false), tuple("Zoë", false));
        assertThat(members.countMembers(home)).isEqualTo(3);
        assertThat(members.countMembers(List.of(home))).containsEntry(home, 3);
    }

    @Test
    void membershipIsUniqueAndShareMemberFindsOnlyMembers() {
        assertThat(members.shareMember(home, ana)).contains(ana);
        assertThat(members.shareMember(home, bob)).isEmpty();
        assertThat(members.isMember(home, ana)).isTrue();
        assertThatThrownBy(() -> TestLists.member(jdbc, home, ana)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void accessIsByMembership() {
        TestLists.member(jdbc, home, bob);
        jdbc.update("insert into tasks (owner_id, list_id, title) values (?, ?, 't')", ana, home);
        long task = jdbc.queryForObject("select max(id) from tasks", Long.class);

        assertThat(lists.findAccessible(home, bob)).isPresent();
        assertThat(lists.findAccessible(home, zoe)).isEmpty();
        assertThat(tasks.findAccessible(task, bob)).isPresent();
        assertThat(tasks.findAccessible(task, zoe)).isEmpty();
        assertThat(lists.findAllForMember(bob)).extracting(TaskList::getId).containsExactly(home);

        members.deleteMember(home, bob);
        assertThat(tasks.findAccessible(task, bob)).isEmpty();
    }

    @Test
    void memberEmailMatchesIgnoringCaseAndAccentsAndOnlyMembers() {
        user("jose@example.com", "José");
        long jose = jdbc.queryForObject("select id from users where email = 'jose@example.com'", Long.class);
        TestLists.member(jdbc, home, jose);

        assertThat(members.isMemberEmail(home, "jose@example.com")).isTrue();
        assertThat(members.isMemberEmail(home, "josé@example.com")).isTrue();
        assertThat(members.isMemberEmail(home, "ana@example.com")).isTrue();
        assertThat(members.isMemberEmail(home, "bob@example.com")).isFalse(); // an account, not a member
        assertThat(members.isMemberEmail(home, "nobody@example.com")).isFalse();
    }

    @Test
    void oneInvitationPerAddressPerListIgnoringCaseAndAccents() {
        invite(home, "jose@example.com", NOW, Duration.ofDays(14));
        assertThatThrownBy(() -> invite(home, "josé@example.com", NOW, Duration.ofDays(14)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_list_invitations_list_email");
        long work = TestLists.other(jdbc, ana, "Work");
        invite(work, "jose@example.com", NOW, Duration.ofDays(14));
        assertThat(members.findInvitation(home, "JOSÉ@example.com")).isPresent();
        assertThatThrownBy(() -> invite(home, "late@example.com", NOW, Duration.ZERO))
                .hasMessageContaining("ck_list_invitations_expiry");
    }

    @Test
    void pendingCountsAndListsOnlyUnexpiredNewestFirst() {
        long old = invite(home, "old@example.com", NOW.minus(Duration.ofDays(15)), Duration.ofDays(14));
        long a = invite(home, "a@example.com", NOW.minusSeconds(60), Duration.ofDays(14));
        long b = invite(home, "b@example.com", NOW, Duration.ofDays(14));

        assertThat(members.pending(home, NOW)).extracting(r -> r.id()).containsExactly(b, a);
        assertThat(members.countPending(home, NOW)).isEqualTo(2);
        // Exactly at expiresAt it is no longer pending.
        assertThat(members.countPending(home, NOW.minusSeconds(60).plus(Duration.ofDays(14)))).isEqualTo(1);

        members.refreshInvitation(old, NOW, NOW.plus(Duration.ofDays(14)));
        assertThat(members.countPending(home, NOW)).isEqualTo(3);
        assertThat(members.findInvitationById(old).orElseThrow().invitedAt()).isEqualTo(NOW);
    }

    @Test
    void myInvitationsMatchMyVerifiedEmailOnlyWithOwnerAndListName() {
        long work = TestLists.other(jdbc, zoe, "Work");
        long first = invite(home, "BOB@example.com", NOW.minusSeconds(10), Duration.ofDays(14));
        long second = invite(work, "bob@example.com", NOW, Duration.ofDays(14));
        invite(TestLists.other(jdbc, zoe, "Old"), "bob@example.com", NOW.minus(Duration.ofDays(20)),
                Duration.ofDays(14));
        invite(TestLists.other(jdbc, zoe, "Other"), "zoe@example.com", NOW, Duration.ofDays(14));

        // Unverified: nothing.
        assertThat(members.mine(bob, NOW)).isEmpty();
        assertThat(members.countMine(bob, NOW)).isZero();
        assertThat(members.findMine(first, bob, NOW)).isEmpty();

        verify(bob);
        assertThat(members.mine(bob, NOW)).containsExactly(
                new MyInvitationRow(second, work, "Work", "Zoë", NOW, NOW.plus(Duration.ofDays(14))),
                new MyInvitationRow(first, home, "Home", "Ana", NOW.minusSeconds(10),
                        NOW.minusSeconds(10).plus(Duration.ofDays(14))));
        assertThat(members.countMine(bob, NOW)).isEqualTo(2);
        assertThat(members.findMine(first, bob, NOW)).hasValueSatisfying(t -> {
            assertThat(t.listId()).isEqualTo(home);
            assertThat(t.ownerId()).isEqualTo(ana);
        });
        // Someone else's invitation, or an expired one, is not mine.
        assertThat(members.findMine(first, zoe, NOW)).isEmpty();
        assertThat(members.findMine(first, bob, NOW.plus(Duration.ofDays(14)))).isEmpty();
    }

    /** AC30 at the database level: a member's account deletion keeps their tasks (owner NULL) and the list's tags. */
    @Test
    void deletingAMembersAccountKeepsTheirTasksAndTheListTagsAndDeletesTheirOwnLists() {
        TestLists.member(jdbc, home, bob);
        long bobsList = TestLists.other(jdbc, bob, "Bob's");
        TestLists.member(jdbc, bobsList, ana);
        jdbc.update("insert into tasks (owner_id, list_id, title) values (?, ?, 'by bob')", bob, home);
        long bobsTask = jdbc.queryForObject("select max(id) from tasks", Long.class);
        jdbc.update("insert into tasks (owner_id, list_id, title) values (?, ?, 'by ana in bobs')", ana, bobsList);
        jdbc.update("insert into tags (owner_id, list_id, name) values (?, ?, 'Shared')", ana, home);
        long tag = jdbc.queryForObject("select max(id) from tags", Long.class);
        jdbc.update("insert into task_tags (task_id, tag_id) values (?, ?)", bobsTask, tag);
        invite(bobsList, "x@example.com", NOW, Duration.ofDays(14));
        invite(home, "bob@example.com", NOW, Duration.ofDays(14));

        assertThat(members.listsTouchedBy(bob, "bob@example.com")).containsExactly(home, bobsList);

        jdbc.update("delete from users where id = ?", bob);

        assertThat(jdbc.queryForObject("select owner_id from tasks where id = ?", Long.class, bobsTask)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from task_tags where task_id = ?", Long.class, bobsTask))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tags where id = ?", Long.class, tag)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from list_members where user_id = ?", Long.class, bob))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from lists where id = ?", Long.class, bobsList)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tasks where list_id = ?", Long.class, bobsList))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from list_invitations where list_id = ?", Long.class,
                bobsList)).isZero();
        assertThat(members.invitationIdsFor("bob@example.com")).hasSize(1); // the app deletes these itself
        assertThat(tasks.findDisplayNames(List.of(ana, bob))).extracting(TaskRepository.UserName::getDisplayName)
                .containsExactly("Ana");
    }

    @Test
    void deletingAListDeletesItsMembersAndInvitations() {
        TestLists.member(jdbc, home, bob);
        invite(home, "x@example.com", NOW, Duration.ofDays(14));

        lists.deleteNow(home);

        assertThat(jdbc.queryForObject("select count(*) from list_members where list_id = ?", Long.class, home))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from list_invitations where list_id = ?", Long.class, home))
                .isZero();
    }
}
