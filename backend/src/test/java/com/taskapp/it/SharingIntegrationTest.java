package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskapp.auth.EmailJobs;
import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.ratelimit.MutableClock;

/**
 * T3b sharing end to end (specs/sharing-team-lists.md AC18-AC30, contract 0.13.0): real HTTP, MySQL 8.4 with the
 * migrations, and Mailpit for the invitation emails. The server clock is a {@link MutableClock} (14-day expiry). The
 * per-inviter limit is raised to 40 so the limit tests fit; one test hits it.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000",
        "app.rate-limit.admin-actions-per-user-per-minute=10000",
        "app.rate-limit.invites-per-user-per-hour=40"})
class SharingIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @Container
    static GenericContainer<?> mailpitContainer = Mailpit.container();

    @DynamicPropertySource
    static void mailServer(DynamicPropertyRegistry registry) {
        Mailpit.register(mailpitContainer, registry);
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    private static final String PASSWORD = "correct-horse";
    private static final String INVITATIONS_LINK = "https://app.taskapp.test/invitations";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    EmailJobs emailJobs;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SecurityLogCapture capture = new SecurityLogCapture();
    private Mailpit mailpit;

    @BeforeEach
    void start() {
        mailpit = new Mailpit(mailpitContainer);
        capture.start();
    }

    @AfterEach
    void stop() {
        capture.stop();
    }

    // ---- AC18, AC21: invite, the invitee's page, the email, accept ----

    @Test
    void inviteEmailInvitationsPageAndAccept() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = verified(signUp("Bob"));
        long home = ana.createList("Home");

        Resp invited = ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"  " + bob.email.toUpperCase()
                + " \"}");
        assertThat(invited.status()).as(invited.body()).isEqualTo(201);
        assertThat(invited.location()).isNull();
        JsonNode invitation = invited.json();
        assertThat(fieldNames(invitation)).containsExactly("id", "email", "invitedAt", "expiresAt");
        assertThat(invitation.get("email").asText()).isEqualTo(bob.email);
        assertThat(Duration.between(Instant.parse(invitation.get("invitedAt").asText()),
                Instant.parse(invitation.get("expiresAt").asText()))).isEqualTo(Duration.ofDays(14));
        assertThat(ana.get("/api/lists/" + home + "/invitations").json()).containsExactly(invitation);

        // The email: subject names the owner, the body the list (quoted), one link to the invitations page.
        Mailpit.Message mail = awaitInvitationEmails(bob.email, 1).get(0);
        assertThat(mail.subject()).isEqualTo(ana.displayName + " invited you to a list on Task App");
        assertThat(mail.text()).contains("Hi " + bob.displayName + ",", "the list \"Home\"", INVITATIONS_LINK,
                "14 days").doesNotContain("token=");

        // The invitee's page and badge.
        JsonNode mine = bob.get("/api/invitations").json();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("id")).isEqualTo(invitation.get("id"));
        assertThat(mine.get(0).get("list").toString()).isEqualTo("{\"id\":" + home + ",\"name\":\"Home\"}");
        assertThat(mine.get(0).get("invitedBy").toString())
                .isEqualTo("{\"displayName\":\"" + ana.displayName + "\"}");
        assertThat(bob.get("/api/invitations/count").body()).isEqualTo("{\"count\":1}");

        Resp accepted = bob.post("/api/invitations/" + invitation.get("id").asLong() + "/accept", null);
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(200);
        assertThat(accepted.json().toString()).isEqualTo("{\"id\":" + home
                + ",\"name\":\"Home\",\"personal\":false,\"role\":\"MEMBER\",\"memberCount\":2}");

        assertThat(bob.get("/api/invitations").json()).isEmpty();
        assertThat(bob.get("/api/invitations/count").body()).isEqualTo("{\"count\":0}");
        assertThat(ana.get("/api/lists/" + home + "/invitations").json()).isEmpty();
        assertThat(names(bob.get("/api/lists").json())).containsExactly("My tasks", "Home");
        assertThat(listEntry(ana.get("/api/lists").json(), home).get("memberCount").asInt()).isEqualTo(2);
        assertThat(listEntry(bob.get("/api/lists").json(), home).get("role").asText()).isEqualTo("MEMBER");

        // AC28: both see the members, owner first, with emails and roles.
        for (Browser who : List.of(ana, bob)) {
            JsonNode members = who.get("/api/lists/" + home + "/members").json();
            assertThat(members).hasSize(2);
            assertThat(members.get(0).get("userId").asLong()).isEqualTo(ana.id);
            assertThat(members.get(0).get("role").asText()).isEqualTo("OWNER");
            assertThat(members.get(0).get("email").asText()).isEqualTo(ana.email);
            assertThat(members.get(1).get("displayName").asText()).isEqualTo(bob.displayName);
            assertThat(members.get(1).get("role").asText()).isEqualTo("MEMBER");
            assertThat(fieldNames(members.get(1))).containsExactly("userId", "displayName", "email", "role",
                    "joinedAt");
        }
        assertThat(bob.get("/api/lists/" + home).json().get("role").asText()).isEqualTo("MEMBER");
    }

    /** AC18: the same answer whether the address has an active account, a disabled one or none. */
    @Test
    void theInviteResponseIsTheSameWithOrWithoutAnAccountAndOnlyAnActiveAccountGetsAnEmail() throws Exception {
        Browser ana = signUp("Ana");
        Browser active = signUp("Active"); // unverified: still gets the email
        Browser disabled = signUp("Disabled");
        jdbc.update("update users set disabled_at = now(6) where id = ?", disabled.id);
        String nobody = "nobody-" + UUID.randomUUID() + "@example.com";
        long home = ana.createList("Home");

        List<JsonNode> responses = new ArrayList<>();
        for (String email : List.of(active.email, disabled.email, nobody)) {
            Resp r = ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"" + email + "\"}");
            assertThat(r.status()).isEqualTo(201);
            assertThat(r.contentType()).isEqualTo("application/json");
            assertThat(r.location()).isNull();
            responses.add(r.json());
        }
        for (JsonNode r : responses) {
            assertThat(fieldNames(r)).containsExactly("id", "email", "invitedAt", "expiresAt");
            assertThat(Duration.between(Instant.parse(r.get("invitedAt").asText()),
                    Instant.parse(r.get("expiresAt").asText()))).isEqualTo(Duration.ofDays(14));
        }
        JsonNode pending = ana.get("/api/lists/" + home + "/invitations").json();
        assertThat(pending).hasSize(3);
        pending.forEach(p -> assertThat(fieldNames(p)).containsExactly("id", "email", "invitedAt", "expiresAt"));

        awaitInvitationEmails(active.email, 1);
        assertThat(invitationEmails(disabled.email)).isEmpty();
        assertThat(invitationEmails(nobody)).isEmpty();
        List<String> outcomes = capture.allLines().stream().filter(l -> l.contains("event=LIST_INVITATION_EMAIL"))
                .toList();
        assertThat(outcomes).anySatisfy(l -> assertThat(l).contains("outcome=sent", active.email))
                .anySatisfy(l -> assertThat(l).contains("outcome=account_disabled", disabled.email))
                .anySatisfy(l -> assertThat(l).contains("outcome=no_account", nobody));
    }

    // ---- AC23, AC24, AC29: roles; 404 for non-members, 403 for members ----

    @Test
    void nonMembersGet404EverywhereAndMembersGet403OnOwnerActions() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = verified(signUp("Bob"));
        Browser eve = signUp("Eve");
        long home = ana.createList("Home");
        join(ana, bob, home);
        long task = ana.createTask("Ana's", home).get("id").asLong();
        long pending = ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"x-" + UUID.randomUUID()
                + "@example.com\"}").json().get("id").asLong();

        // A non-member: 404 for everything about the list or its tasks, never 403.
        for (Resp r : List.of(eve.get("/api/lists/" + home), eve.get("/api/lists/" + home + "/members"),
                eve.get("/api/lists/" + home + "/invitations"), eve.get("/api/tasks?list=" + home),
                eve.get("/api/tags?list=" + home), eve.patch("/api/lists/" + home, "{\"name\":\"x\"}"),
                eve.delete("/api/lists/" + home), eve.post("/api/lists/" + home + "/leave", null),
                eve.post("/api/lists/" + home + "/invitations", "{\"email\":\"y@example.com\"}"),
                eve.delete("/api/lists/" + home + "/invitations/" + pending),
                eve.delete("/api/lists/" + home + "/members/" + bob.id),
                eve.post("/api/tasks", "{\"title\":\"x\",\"listId\":" + home + "}"))) {
            expectProblem(r, 404, "LIST_NOT_FOUND", "List not found");
        }
        expectProblem(eve.get("/api/tasks/" + task), 404, "NOT_FOUND", "Task not found");
        expectProblem(eve.put("/api/tasks/" + task, "{\"title\":\"x\"}"), 404, "NOT_FOUND", "Task not found");

        // A member: 403 on the owner's actions, nothing changes.
        for (Resp r : List.of(bob.patch("/api/lists/" + home, "{\"name\":\"x\"}"), bob.delete("/api/lists/" + home),
                bob.get("/api/lists/" + home + "/invitations"),
                bob.post("/api/lists/" + home + "/invitations", "{\"email\":\"y@example.com\"}"),
                bob.delete("/api/lists/" + home + "/invitations/" + pending),
                bob.delete("/api/lists/" + home + "/members/" + ana.id),
                bob.delete("/api/lists/" + home + "/members/" + bob.id))) {
            expectProblem(r, 403, "OWNER_ONLY", "Only the list owner can do this");
        }
        assertThat(ana.get("/api/lists/" + home).json().get("name").asText()).isEqualTo("Home");
        assertThat(ana.get("/api/lists/" + home + "/invitations").json()).hasSize(1);

        // AC23: a member can add, edit, tick, tag, move and delete any task.
        long bobs = bob.createTask("Bob's", home).get("id").asLong();
        Resp edited = bob.put("/api/tasks/" + task, "{\"title\":\"Edited by Bob\",\"tags\":[\"Shared\"]}");
        assertThat(edited.status()).as(edited.body()).isEqualTo(200);
        assertThat(edited.json().get("addedBy").get("displayName").asText()).isEqualTo(ana.displayName);
        assertThat(bob.patch("/api/tasks/" + task, "{\"status\":\"DONE\"}").status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select owner_id from tags where list_id = ?", Long.class, home))
                .as("tag owner = list owner").isEqualTo(ana.id);
        assertThat(names(bob.get("/api/tags?list=" + home).json())).containsExactly("Shared");
        long bobsMine = bob.myTasks();
        Resp moved = bob.put("/api/tasks/" + bobs, "{\"title\":\"Bob's\",\"listId\":" + bobsMine + "}");
        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.json().get("addedBy").get("displayName").asText()).isEqualTo(bob.displayName);
        assertThat(bob.put("/api/tasks/" + bobs, "{\"title\":\"Bob's\",\"listId\":" + home + "}").status())
                .isEqualTo(200);
        assertThat(bob.delete("/api/tasks/" + task).status()).isEqualTo(204);
        // Moving into a list the member isn't in: 404 LIST_NOT_FOUND.
        expectProblem(bob.put("/api/tasks/" + bobs, "{\"title\":\"x\",\"listId\":" + eve.myTasks() + "}"), 404,
                "LIST_NOT_FOUND", "List not found");
    }

    /** A5 AC10: an admin who is not a member sees nothing. */
    @Test
    void adminsSeeNoListTheyAreNotAMemberOf() throws Exception {
        Browser ana = signUp("Ana");
        Browser admin = signUp("Admin");
        jdbc.update("update users set role = 'SUPER_ADMIN' where id = ?", admin.id);
        long home = ana.createList("Home");

        expectProblem(admin.get("/api/lists/" + home), 404, "LIST_NOT_FOUND", "List not found");
        expectProblem(admin.get("/api/lists/" + home + "/members"), 404, "LIST_NOT_FOUND", "List not found");
    }

    // ---- AC19: limits and duplicates ----

    @Test
    void duplicatesAndMyTasksAreRefusedWithClearMessages() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = verified(signUp("Bob"));
        long home = ana.createList("Home");
        join(ana, bob, home);

        expectProblem(ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"" + ana.email + "\"}"), 409,
                "ALREADY_MEMBER", "This person is already a member of the list");
        expectProblem(ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"" + bob.email.toUpperCase()
                + "\"}"), 409, "ALREADY_MEMBER", "This person is already a member of the list");
        String carol = "carol-" + UUID.randomUUID() + "@example.com";
        assertThat(ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"" + carol + "\"}").status())
                .isEqualTo(201);
        expectProblem(ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"" + carol.toUpperCase() + "\"}"),
                409, "ALREADY_INVITED", "This email address already has a pending invitation");
        expectProblem(ana.post("/api/lists/" + ana.myTasks() + "/invitations", "{\"email\":\"" + carol + "\"}"), 409,
                "PERSONAL_LIST", "Your personal list can't be shared");
        Resp invalid = ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"nope\"}");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.json().get("errors").get(0).get("code").asText()).isEqualTo("INVALID_EMAIL");
        assertThat(ana.get("/api/lists/" + home + "/invitations").json()).hasSize(1);
    }

    @Test
    void twentyPendingInvitationsAndTwentyMembersAtMost() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        for (int i = 0; i < 20; i++) {
            assertThat(ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"p" + i + "-" + home
                    + "@example.com\"}").status()).isEqualTo(201);
        }
        expectProblem(ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"one-more@example.com\"}"), 409,
                "INVITATION_LIMIT", "A list can have at most 20 pending invitations");

        // 20 members: 18 added directly, plus one invitee who accepts at 19 -> 20.
        long team = ana.createList("Team");
        for (long user : insertUsers(18)) {
            jdbc.update("insert into list_members (list_id, user_id) values (?, ?)", team, user);
        }
        Browser bob = verified(signUp("Bob"));
        Browser carol = verified(signUp("Carol"));
        long toBob = ana.post("/api/lists/" + team + "/invitations", "{\"email\":\"" + bob.email + "\"}").json()
                .get("id").asLong();
        long toCarol = ana.post("/api/lists/" + team + "/invitations", "{\"email\":\"" + carol.email + "\"}")
                .json().get("id").asLong();
        assertThat(bob.post("/api/invitations/" + toBob + "/accept", null).json().get("memberCount").asInt())
                .isEqualTo(20);
        expectProblem(carol.post("/api/invitations/" + toCarol + "/accept", null), 409, "MEMBER_LIMIT",
                "A list can have at most 20 members");
        assertThat(carol.get("/api/invitations").json()).as("kept").hasSize(1);
        expectProblem(ana.post("/api/lists/" + team + "/invitations", "{\"email\":\"z@example.com\"}"), 409,
                "MEMBER_LIMIT", "A list can have at most 20 members");
        assertThat(count("select count(*) from list_members where list_id = ?", team)).isEqualTo(20);

        // Room again after a removal: the kept invitation can be accepted.
        assertThat(ana.delete("/api/lists/" + team + "/members/" + bob.id).status()).isEqualTo(204);
        assertThat(carol.post("/api/invitations/" + toCarol + "/accept", null).status()).isEqualTo(200);
    }

    /** Q17: the 50-list limit counts owned lists only; lists shared with me don't count and aren't capped. */
    @Test
    void joinedListsDontCountTowardsTheFiftyOwnedLists() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = verified(signUp("Bob"));
        long shared = ana.createList("Shared");
        join(ana, bob, shared);
        for (int i = 2; i <= 50; i++) {
            assertThat(bob.post("/api/lists", "{\"name\":\"L" + i + "\"}").status()).isEqualTo(201);
        }
        expectProblem(bob.post("/api/lists", "{\"name\":\"L51\"}"), 409, "LIST_LIMIT", "You can have at most 50 lists");
        JsonNode lists = bob.get("/api/lists").json();
        assertThat(lists).hasSize(51);
        assertThat(lists.get(0).get("personal").asBoolean()).isTrue();
        assertThat(listEntry(lists, shared).get("role").asText()).isEqualTo("MEMBER");
    }

    // ---- AC20, AC22: cancel, expiry, stale invitations ----

    @Test
    void anExpiredInvitationIsGoneAndReInvitingRefreshesIt() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = verified(signUp("Bob"));
        long home = ana.createList("Home");
        JsonNode first = ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"" + bob.email + "\"}").json();
        long id = first.get("id").asLong();

        clock.advance(Duration.ofDays(14));
        ana.login(ana); // the sessions ended (12-hour maximum)
        bob.login(bob);
        assertThat(ana.get("/api/lists/" + home + "/invitations").json()).isEmpty();
        assertThat(bob.get("/api/invitations").json()).isEmpty();
        assertThat(bob.get("/api/invitations/count").body()).isEqualTo("{\"count\":0}");
        expectProblem(bob.post("/api/invitations/" + id + "/accept", null), 404, "INVITATION_NOT_FOUND",
                "This invitation is no longer valid");
        expectProblem(bob.post("/api/invitations/" + id + "/decline", null), 404, "INVITATION_NOT_FOUND",
                "This invitation is no longer valid");

        Resp again = ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"" + bob.email + "\"}");
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.json().get("id").asLong()).isEqualTo(id);
        assertThat(Instant.parse(again.json().get("invitedAt").asText()))
                .isAfter(Instant.parse(first.get("invitedAt").asText()).plus(Duration.ofDays(13)));
        assertThat(count("select count(*) from list_invitations where list_id = ?", home)).isEqualTo(1);
        assertThat(bob.post("/api/invitations/" + id + "/accept", null).status()).isEqualTo(200);
    }

    @Test
    void cancelledDeclinedOrDeletedListInvitationsAreNoLongerValid() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = verified(signUp("Bob"));
        long home = ana.createList("Home");
        long work = ana.createList("Work");
        long gone = ana.createList("Gone");
        long toHome = invite(ana, home, bob);
        long toWork = invite(ana, work, bob);
        long toGone = invite(ana, gone, bob);
        assertThat(bob.get("/api/invitations/count").body()).isEqualTo("{\"count\":3}");

        assertThat(ana.delete("/api/lists/" + home + "/invitations/" + toHome).status()).isEqualTo(204);
        expectProblem(ana.delete("/api/lists/" + home + "/invitations/" + toHome), 404, "INVITATION_NOT_FOUND",
                "This invitation is no longer valid");
        expectProblem(ana.delete("/api/lists/" + home + "/invitations/" + toWork), 404, "INVITATION_NOT_FOUND",
                "This invitation is no longer valid"); // another list's invitation
        expectProblem(bob.post("/api/invitations/" + toHome + "/accept", null), 404, "INVITATION_NOT_FOUND",
                "This invitation is no longer valid");

        assertThat(bob.post("/api/invitations/" + toWork + "/decline", null).status()).isEqualTo(204);
        assertThat(ana.get("/api/lists/" + work + "/invitations").json()).isEmpty();
        expectProblem(bob.post("/api/invitations/" + toWork + "/accept", null), 404, "INVITATION_NOT_FOUND",
                "This invitation is no longer valid");

        assertThat(ana.delete("/api/lists/" + gone).status()).isEqualTo(204);
        expectProblem(bob.post("/api/invitations/" + toGone + "/accept", null), 404, "INVITATION_NOT_FOUND",
                "This invitation is no longer valid");
        assertThat(bob.get("/api/invitations").json()).isEmpty();
        assertThat(names(bob.get("/api/lists").json())).containsExactly("My tasks");
        // Someone else can't accept or decline my invitation.
        long toWorkAgain = invite(ana, work, bob);
        Browser eve = verified(signUp("Eve"));
        expectProblem(eve.post("/api/invitations/" + toWorkAgain + "/accept", null), 404, "INVITATION_NOT_FOUND",
                "This invitation is no longer valid");
        expectProblem(eve.post("/api/invitations/" + toWorkAgain + "/decline", null), 404, "INVITATION_NOT_FOUND",
                "This invitation is no longer valid");
    }

    @Test
    void anUnverifiedAccountSeesNoInvitationUntilItConfirmsItsEmail() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long home = ana.createList("Home");
        long id = invite(ana, home, bob);

        assertThat(bob.get("/api/invitations").json()).isEmpty();
        assertThat(bob.get("/api/invitations/count").body()).isEqualTo("{\"count\":0}");
        expectProblem(bob.post("/api/invitations/" + id + "/accept", null), 404, "INVITATION_NOT_FOUND",
                "This invitation is no longer valid");
        expectProblem(bob.post("/api/invitations/" + id + "/decline", null), 404, "INVITATION_NOT_FOUND",
                "This invitation is no longer valid");

        verified(bob);
        assertThat(bob.get("/api/invitations/count").body()).isEqualTo("{\"count\":1}");
        assertThat(bob.post("/api/invitations/" + id + "/accept", null).status()).isEqualTo(200);
    }

    /** Someone who registers after being invited (and confirms) sees the invitation; they got no email. */
    @Test
    void anAddressRegisteredAfterTheInviteSeesIt() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        String email = "later-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        assertThat(ana.post("/api/lists/" + home + "/invitations", "{\"email\":\"" + email + "\"}").status())
                .isEqualTo(201);
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(emailJobs::isIdle);

        Browser later = verified(signUp("Later", email));
        assertThat(later.get("/api/invitations").json()).hasSize(1);
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(emailJobs::isIdle);
        assertThat(invitationEmails(email)).isEmpty();
    }

    @Test
    void acceptingWhenAlreadyAMemberOnlyRemovesTheInvitation() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = verified(signUp("Bob"));
        long home = ana.createList("Home");
        join(ana, bob, home);
        // An invitation that can't be created through the API any more (Bob is a member), e.g. left over.
        jdbc.update("insert into list_invitations (list_id, email, invited_at, expires_at)"
                + " values (?, ?, ?, ?)", home, bob.email, java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(14))));
        long id = bob.get("/api/invitations").json().get(0).get("id").asLong();

        Resp accepted = bob.post("/api/invitations/" + id + "/accept", null);
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.json().get("role").asText()).isEqualTo("MEMBER");
        assertThat(accepted.json().get("memberCount").asInt()).isEqualTo(2);
        assertThat(count("select count(*) from list_invitations where id = ?", id)).isZero();
        assertThat(count("select count(*) from list_members where list_id = ? and user_id = ?", home, bob.id))
                .isEqualTo(1);
    }

    // ---- AC25, AC26, AC27: leave, remove, "Added by" ----

    @Test
    void leaveAndRemoveKeepTheTasksAndEndAccess() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = verified(signUp("Bob"));
        Browser carol = verified(signUp("Carol"));
        long home = ana.createList("Home");
        join(ana, bob, home);
        join(ana, carol, home);
        long bobs = bob.createTask("by Bob", home).get("id").asLong();
        long carols = carol.createTask("by Carol", home).get("id").asLong();

        expectProblem(ana.post("/api/lists/" + home + "/leave", null), 409, "OWNER_CANNOT_LEAVE",
                "The owner can't leave the list");
        expectProblem(ana.delete("/api/lists/" + home + "/members/" + ana.id), 409, "OWNER_CANNOT_LEAVE",
                "The owner can't leave the list");
        expectProblem(ana.post("/api/lists/" + ana.myTasks() + "/leave", null), 409, "OWNER_CANNOT_LEAVE",
                "The owner can't leave the list");

        assertThat(bob.post("/api/lists/" + home + "/leave", null).status()).isEqualTo(204);
        expectProblem(bob.post("/api/lists/" + home + "/leave", null), 404, "LIST_NOT_FOUND", "List not found");
        expectProblem(bob.get("/api/lists/" + home), 404, "LIST_NOT_FOUND", "List not found");
        expectProblem(bob.get("/api/tasks/" + bobs), 404, "NOT_FOUND", "Task not found");
        assertThat(names(bob.get("/api/lists").json())).containsExactly("My tasks");

        assertThat(ana.delete("/api/lists/" + home + "/members/" + carol.id).status()).isEqualTo(204);
        expectProblem(ana.delete("/api/lists/" + home + "/members/" + carol.id), 404, "MEMBER_NOT_FOUND",
                "Member not found");
        expectProblem(carol.get("/api/tasks?list=" + home), 404, "LIST_NOT_FOUND", "List not found");
        expectProblem(carol.patch("/api/tasks/" + carols, "{\"done\":true}"), 404, "NOT_FOUND", "Task not found");

        // Their tasks stay, still "Added by" them.
        JsonNode items = ana.get("/api/tasks?list=" + home).json().get("items");
        assertThat(items).hasSize(2);
        Map<String, String> addedBy = new LinkedHashMap<>();
        items.forEach(t -> addedBy.put(t.get("title").asText(), t.get("addedBy").get("displayName").asText()));
        assertThat(addedBy).containsEntry("by Bob", bob.displayName).containsEntry("by Carol", carol.displayName);
        assertThat(ana.get("/api/lists/" + home).json().get("memberCount").asInt()).isEqualTo(1);
        // A removed member can be invited again.
        assertThat(invite(ana, home, carol)).isPositive();
    }

    // ---- AC30: account deletion ----

    @Test
    void aMembersAccountDeletionKeepsTheirTasksAndTheListsTagsAndDeletesTheirOwnLists() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = verified(signUp("Bob"));
        long home = ana.createList("Home");
        join(ana, bob, home);
        long task = bob.createTask("by Bob", home).get("id").asLong();
        assertThat(bob.put("/api/tasks/" + task, "{\"title\":\"by Bob\",\"tags\":[\"Groceries\"]}").status())
                .isEqualTo(200);
        long bobsList = bob.createList("Bob's");
        verified(ana);
        join(bob, ana, bobsList);
        ana.createTask("Ana in Bob's", bobsList);
        long pendingForBob = invite(ana, ana.createList("Later"), bob);

        assertThat(bob.post("/api/account/delete", "{\"password\":\"" + PASSWORD + "\"}").status()).isEqualTo(204);

        JsonNode kept = ana.get("/api/tasks/" + task).json();
        assertThat(kept.get("addedBy").isNull()).isTrue();
        assertThat(kept.get("tags").toString()).isEqualTo("[\"Groceries\"]");
        assertThat(names(ana.get("/api/tags?list=" + home).json())).containsExactly("Groceries");
        expectProblem(ana.get("/api/lists/" + bobsList), 404, "LIST_NOT_FOUND", "List not found");
        assertThat(names(ana.get("/api/lists").json())).containsExactly("My tasks", "Home", "Later");
        assertThat(ana.get("/api/lists/" + home).json().get("memberCount").asInt()).isEqualTo(1);
        assertThat(count("select count(*) from list_invitations where id = ?", pendingForBob)).isZero();
        assertThat(count("select count(*) from tasks where list_id = ?", bobsList)).isZero();
    }

    @Test
    void anAdminDeletingAMemberKeepsTheirTasksToo() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = verified(signUp("Bob"));
        Browser admin = signUp("Admin");
        jdbc.update("update users set role = 'SUPER_ADMIN' where id = ?", admin.id);
        Browser adminSession = new Browser().start();
        adminSession.login(admin);
        long home = ana.createList("Home");
        join(ana, bob, home);
        long task = bob.createTask("by Bob", home).get("id").asLong();

        assertThat(adminSession.delete("/api/admin/users/" + bob.id).status()).isEqualTo(204);

        assertThat(ana.get("/api/tasks/" + task).json().get("addedBy").isNull()).isTrue();
    }

    // ---- AC19: rate limits ----

    @Test
    void invitesAreRateLimitedPerInviterAndEmailsPerRecipientSilently() throws Exception {
        // Per recipient: six owners invite the same account; the invitations all exist, five emails go out.
        Browser target = signUp("Target");
        for (int i = 0; i < 6; i++) {
            Browser owner = signUp("Owner");
            long list = owner.createList("L" + i);
            assertThat(owner.post("/api/lists/" + list + "/invitations", "{\"email\":\"" + target.email + "\"}")
                    .status()).isEqualTo(201);
        }
        awaitInvitationEmails(target.email, 5);
        assertThat(capture.allLines()).anySatisfy(l -> assertThat(l).contains("event=LIST_INVITATION_EMAIL",
                "outcome=account_limit"));
        assertThat(count("select count(*) from list_invitations where email = ?", target.email)).isEqualTo(6);

        // Per inviter (40 in this test): refusals after the checks count too; the 41st is a 429.
        Browser ana = signUp("Ana");
        long a = ana.createList("A");
        long b = ana.createList("B");
        for (int i = 0; i < 20; i++) {
            assertThat(ana.post("/api/lists/" + a + "/invitations", "{\"email\":\"r" + i + "-" + a
                    + "@example.com\"}").status()).isEqualTo(201);
        }
        for (int i = 0; i < 20; i++) {
            int status = ana.post("/api/lists/" + (i < 19 ? b : a) + "/invitations", "{\"email\":\"s" + i + "-" + b
                    + "@example.com\"}").status();
            assertThat(status).isEqualTo(i < 19 ? 201 : 409); // the 40th: INVITATION_LIMIT on A, still counted
        }
        Resp limited = ana.post("/api/lists/" + b + "/invitations", "{\"email\":\"t-" + b + "@example.com\"}");
        expectProblem(limited, 429, "RATE_LIMITED", "Too many attempts. Please try again later.");
        assertThat(limited.retryAfter()).isNotNull();
        // Not counted: a 404, 403 or PERSONAL_LIST answer comes first.
        expectProblem(ana.post("/api/lists/" + ana.myTasks() + "/invitations", "{\"email\":\"u@example.com\"}"), 409,
                "PERSONAL_LIST", "Your personal list can't be shared");
    }

    // ---- helpers ----

    /** Invitation emails to {@code address} (registration also sends a confirmation email), oldest first. */
    private List<Mailpit.Message> invitationEmails(String address) {
        return mailpit.to(address).stream().filter(m -> m.subject().endsWith(" invited you to a list on Task App"))
                .toList();
    }

    /** Waits until the email jobs are done, then expects exactly {@code count} invitation emails. */
    private List<Mailpit.Message> awaitInvitationEmails(String address, int count) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(invitationEmails(address)).hasSizeGreaterThanOrEqualTo(count));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(emailJobs::isIdle);
        List<Mailpit.Message> found = invitationEmails(address);
        assertThat(found).hasSize(count);
        return found;
    }

    private long invite(Browser owner, long listId, Browser invitee) throws Exception {
        Resp r = owner.post("/api/lists/" + listId + "/invitations", "{\"email\":\"" + invitee.email + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().get("id").asLong();
    }

    /** {@code member} (verified) joins {@code owner}'s list through an invitation. */
    private void join(Browser owner, Browser member, long listId) throws Exception {
        long id = invite(owner, listId, member);
        Resp r = member.post("/api/invitations/" + id + "/accept", null);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
    }

    private Browser verified(Browser b) {
        jdbc.update("update users set email_verified_at = now(6) where id = ?", b.id);
        return b;
    }

    private List<Long> insertUsers(int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String s = UUID.randomUUID().toString().substring(0, 12);
            jdbc.update("insert into users (email, password_hash, display_name) values (?, 'h', ?)",
                    "m-" + s + "@example.com", "Member " + s);
            ids.add(jdbc.queryForObject("select id from users where email = ?", Long.class, "m-" + s + "@example.com"));
        }
        return ids;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static JsonNode listEntry(JsonNode lists, long id) {
        for (JsonNode l : lists) {
            if (l.get("id").asLong() == id) {
                return l;
            }
        }
        throw new AssertionError("list " + id + " not in " + lists);
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
            out.add(it.next());
        }
        return out;
    }

    private static void expectProblem(Resp r, int status, String code, String detail) throws IOException {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.json().get("code").asText()).isEqualTo(code);
        assertThat(r.json().get("detail").asText()).isEqualTo(detail);
    }

    private Browser signUp(String name) throws Exception {
        return signUp(name, name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
    }

    private Browser signUp(String name, String email) throws Exception {
        Browser b = new Browser().start();
        b.email = email;
        b.displayName = name + " " + UUID.randomUUID().toString().substring(0, 8);
        Resp r = b.post("/api/auth/register", "{\"displayName\":\"" + b.displayName + "\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        b.id = r.json().get("id").asLong();
        return b;
    }

    /** Minimal browser: cookies, XSRF header like Angular. Any 5xx fails the test. */
    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        long id;
        String email;
        String displayName;

        Browser start() throws Exception {
            get("/api/auth/me");
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        void login(Browser account) throws Exception {
            Resp r = post("/api/auth/login", "{\"email\":\"" + account.email + "\",\"password\":\"" + PASSWORD + "\"}");
            assertThat(r.status()).as(r.body()).isEqualTo(200);
            id = account.id;
            email = account.email;
        }

        long myTasks() throws Exception {
            return get("/api/lists").json().get(0).get("id").asLong();
        }

        long createList(String name) throws Exception {
            Resp r = post("/api/lists", "{\"name\":\"" + name + "\"}");
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            return r.json().get("id").asLong();
        }

        JsonNode createTask(String title, Long listId) throws Exception {
            Resp r = post("/api/tasks", "{\"title\":\"" + title + "\"" + (listId == null ? "" : ",\"listId\":" + listId)
                    + "}");
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            return r.json();
        }

        Resp get(String path) throws Exception {
            return send("GET", path, null);
        }

        Resp post(String path, String body) throws Exception {
            return send("POST", path, body);
        }

        Resp put(String path, String body) throws Exception {
            return send("PUT", path, body);
        }

        Resp patch(String path, String body) throws Exception {
            return send("PATCH", path, body);
        }

        Resp delete(String path) throws Exception {
            return send("DELETE", path, null);
        }

        Resp send(String method, String path, String body) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) {
                b.header("Content-Type", "application/json");
            }
            synchronized (cookies) {
                if (!cookies.isEmpty()) {
                    b.header("Cookie", cookies.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
                }
                if (!"GET".equals(method) && cookies.containsKey("XSRF-TOKEN")) {
                    b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
                }
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            synchronized (cookies) {
                for (String sc : r.headers().allValues("Set-Cookie")) {
                    String[] nameValue = sc.split(";", 2)[0].split("=", 2);
                    if (sc.contains("Max-Age=0")) {
                        cookies.remove(nameValue[0]);
                    } else {
                        cookies.put(nameValue[0], nameValue[1]);
                    }
                }
            }
            if (r.statusCode() >= 500) {
                throw new AssertionError(method + " " + path + " -> " + r.statusCode() + " " + r.body());
            }
            return new Resp(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""),
                    r.headers().firstValue("Location").orElse(null),
                    r.headers().firstValue("Retry-After").orElse(null), r.body());
        }
    }

    record Resp(int status, String contentType, String location, String retryAfter, String body) {
        JsonNode json() throws IOException {
            return JSON.readTree(body);
        }
    }
}
