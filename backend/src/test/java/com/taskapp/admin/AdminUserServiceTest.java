package com.taskapp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.taskapp.admin.dto.AdminUserPage;
import com.taskapp.admin.dto.AdminUserQuery;
import com.taskapp.admin.dto.AdminUserResponse;
import com.taskapp.admin.dto.ChangeRoleRequest;
import com.taskapp.auth.AccountService;
import com.taskapp.auth.EmailTokenPurpose;
import com.taskapp.auth.EmailTokenRepository;
import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.auth.RememberMeTokenRepository;
import com.taskapp.auth.UnauthenticatedException;
import com.taskapp.auth.User;
import com.taskapp.auth.UserConnection;
import com.taskapp.auth.UserConnectionRepository;
import com.taskapp.auth.UserRepository;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.Role;
import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.ratelimit.AdminRateLimitProperties;
import com.taskapp.security.ratelimit.AdminRateLimiter;
import com.taskapp.security.ratelimit.RateLimitedException;

import jakarta.validation.Validation;

/** The rules of specs/roles-admin.md (Roles table, AC11-AC17, AC20) against mocked repositories. */
class AdminUserServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00.123456Z");
    private static final long ACTOR = 1L;
    private static final long TARGET = 42L;
    private static final AuthenticatedUser ME = new AuthenticatedUser(ACTOR, "admin@example.com", "Admin");

    private final UserRepository users = mock(UserRepository.class);
    private final UserConnectionRepository connections = mock(UserConnectionRepository.class);
    private final RememberMeTokenRepository rememberMeTokens = mock(RememberMeTokenRepository.class);
    private final EmailTokenRepository emailTokens = mock(EmailTokenRepository.class);
    private final AccountService accounts = mock(AccountService.class);
    private final SecurityLogCapture capture = new SecurityLogCapture().start();

    private AdminRateLimiter limiter = new AdminRateLimiter(new AdminRateLimitProperties(1000, 1000),
            Clock.systemUTC());

    private AdminUserService service() {
        return new AdminUserService(users, connections, rememberMeTokens, emailTokens, accounts, limiter,
                Clock.fixed(NOW, ZoneOffset.UTC), Validation.buildDefaultValidatorFactory().getValidator(),
                mock(PlatformTransactionManager.class));
    }

    @AfterEach
    void stop() {
        capture.stop();
    }

    private static User user(long id, String email, Role role) {
        User user = new User(email, "$2a$10$" + "x".repeat(53), email.substring(0, email.indexOf('@')));
        ReflectionTestUtils.setField(user, "id", id);
        user.changeRole(role);
        return user;
    }

    /** The locked rows: the actor and (if not null) the target. */
    private void locked(User actor, User target) {
        List<User> rows = new ArrayList<>();
        if (actor != null) {
            rows.add(actor);
        }
        if (target != null) {
            rows.add(target);
        }
        when(users.findAllByIdForUpdate(List.of(ACTOR, TARGET))).thenReturn(rows);
    }

    private List<String> audit(String event) {
        return capture.lines().stream().filter(l -> l.startsWith("SECURITY event=" + event + " ")).toList();
    }

    // ---- Roles table ----

    enum Action {
        DISABLE((s, t) -> s.disable(ME, t)),
        ENABLE((s, t) -> s.enable(ME, t)),
        REVOKE((s, t) -> s.revokeSessions(ME, t)),
        ROLE((s, t) -> s.changeRole(ME, t, new ChangeRoleRequest("ADMIN"))),
        DELETE((s, t) -> {
            s.delete(ME, t);
            return null;
        });

        final BiFunction<AdminUserService, Long, AdminUserResponse> call;

        Action(BiFunction<AdminUserService, Long, AdminUserResponse> call) {
            this.call = call;
        }
    }

    /** actor role, target role, action, allowed (else 403 INSUFFICIENT_ROLE with this reason). */
    static Stream<Arguments> rolesTable() {
        List<Arguments> cases = new ArrayList<>();
        for (Action action : Action.values()) {
            boolean roleChange = action == Action.ROLE;
            cases.add(Arguments.of(Role.ADMIN, Role.USER, action, !roleChange, "insufficient_role"));
            cases.add(Arguments.of(Role.ADMIN, Role.ADMIN, action, false, "insufficient_role"));
            cases.add(Arguments.of(Role.ADMIN, Role.SUPER_ADMIN, action, false, "target_super_admin"));
            cases.add(Arguments.of(Role.SUPER_ADMIN, Role.USER, action, true, null));
            cases.add(Arguments.of(Role.SUPER_ADMIN, Role.ADMIN, action, true, null));
            cases.add(Arguments.of(Role.SUPER_ADMIN, Role.SUPER_ADMIN, action, false, "target_super_admin"));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {2} on {1}: allowed={3}")
    @MethodSource("rolesTable")
    void rolesTable(Role actorRole, Role targetRole, Action action, boolean allowed, String reason) {
        User target = user(TARGET, "alice@example.com", targetRole);
        if (action == Action.ENABLE) {
            target.disable(NOW.minusSeconds(60));
        }
        locked(user(ACTOR, "admin@example.com", actorRole), target);

        if (allowed) {
            action.call.apply(service(), TARGET);
            assertThat(audit("ADMIN_ACCESS_DENIED")).isEmpty();
            // Setting ADMIN on an ADMIN is allowed but changes (and logs) nothing.
            boolean noOp = action == Action.ROLE && targetRole == Role.ADMIN;
            assertThat(capture.lines()).hasSize(noOp ? 0 : 1);
        } else {
            assertThatThrownBy(() -> action.call.apply(service(), TARGET))
                    .isInstanceOf(InsufficientRoleException.class);
            assertThat(capture.lines()).containsExactly("SECURITY event=ADMIN_ACCESS_DENIED actor_id=1 target_id=42"
                    + " action=" + action.name().toLowerCase().replace("revoke", "revoke_sessions")
                            .replace("role", "change_role")
                    + " reason=" + reason + " email=admin@example.com ip=- path=-");
            verify(users, never()).saveAndFlush(any());
            verifyNoInteractions(rememberMeTokens, emailTokens, accounts);
            assertThat(target.getRole()).isEqualTo(targetRole);
        }
    }

    @ParameterizedTest
    @MethodSource("actions")
    void actingOnYourselfIs409WithoutTouchingTheDatabase(Action action) {
        assertThatThrownBy(() -> action.call.apply(service(), ACTOR)).isInstanceOf(CannotModifySelfException.class);

        verifyNoInteractions(users, rememberMeTokens, emailTokens, accounts);
        assertThat(capture.lines()).singleElement().asString()
                .startsWith("SECURITY event=ADMIN_ACCESS_DENIED actor_id=1 target_id=1 action=")
                .contains("reason=self");
    }

    static Stream<Action> actions() {
        return Stream.of(Action.values());
    }

    @Test
    void anActorWhoseLockedRoleIsUserNowGets403Forbidden() {
        locked(user(ACTOR, "admin@example.com", Role.USER), user(TARGET, "alice@example.com", Role.USER));

        assertThatThrownBy(() -> service().disable(ME, TARGET)).isInstanceOf(NotAdminException.class);

        assertThat(capture.lines()).containsExactly("SECURITY event=ADMIN_ACCESS_DENIED actor_id=1 target_id=42"
                + " action=disable reason=not_admin email=admin@example.com ip=- path=-");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void anActorWhoIsGoneIs401AndADisabledOne401AccountDisabled() {
        locked(null, user(TARGET, "alice@example.com", Role.USER));
        assertThatThrownBy(() -> service().disable(ME, TARGET)).isInstanceOf(UnauthenticatedException.class);

        User disabledAdmin = user(ACTOR, "admin@example.com", Role.SUPER_ADMIN);
        disabledAdmin.disable(NOW.minusSeconds(5));
        locked(disabledAdmin, user(TARGET, "alice@example.com", Role.USER));
        assertThatThrownBy(() -> service().disable(ME, TARGET)).isInstanceOf(ActorDisabledException.class);

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void aTargetThatIsGoneIs404() {
        locked(user(ACTOR, "admin@example.com", Role.SUPER_ADMIN), null);

        assertThatThrownBy(() -> service().delete(ME, TARGET)).isInstanceOf(UserNotFoundException.class);

        assertThat(capture.lines()).containsExactly("SECURITY event=ADMIN_ACCESS_DENIED actor_id=1 target_id=42"
                + " action=delete reason=target_not_found email=admin@example.com ip=- path=-");
        verifyNoInteractions(accounts, rememberMeTokens);
    }

    @Test
    void locksBothRowsInIdOrderEvenWhenTheTargetIdIsSmaller() {
        User target = user(0L, "alice@example.com", Role.USER);
        when(users.findAllByIdForUpdate(List.of(0L, ACTOR)))
                .thenReturn(List.of(target, user(ACTOR, "admin@example.com", Role.ADMIN)));
        when(users.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service().enable(ME, 0L);

        verify(users).findAllByIdForUpdate(List.of(0L, ACTOR));
    }

    // ---- what each change does ----

    @Test
    void disableEndsSessionsDeletesLoginsAndResetLinksUsersRowFirstThenAudits() {
        User target = user(TARGET, "alice@example.com", Role.USER);
        locked(user(ACTOR, "admin@example.com", Role.ADMIN), target);
        when(connections.findByUserIdAndProvider(TARGET, UserConnection.GOOGLE)).thenReturn(Optional.empty());

        AdminUserResponse response = service().disable(ME, TARGET);

        assertThat(response.disabled()).isTrue();
        assertThat(response.id()).isEqualTo(TARGET);
        assertThat(target.getDisabledAt()).isEqualTo(NOW);
        assertThat(target.getSessionsValidAfter()).isEqualTo(NOW);
        InOrder order = inOrder(users, rememberMeTokens, emailTokens);
        order.verify(users).findAllByIdForUpdate(List.of(ACTOR, TARGET));
        order.verify(users).saveAndFlush(target);
        order.verify(rememberMeTokens).deleteAllForUser(TARGET);
        order.verify(emailTokens).deleteReplacedAndExpired(TARGET, EmailTokenPurpose.RESET_PASSWORD, NOW);
        assertThat(capture.lines()).containsExactly("SECURITY event=ADMIN_USER_DISABLED actor_id=1 target_id=42"
                + " action=disable email=admin@example.com ip=- path=-");
        assertThat(capture.events().get(0).getLevel().toString()).isEqualTo("INFO");
    }

    @Test
    void disablingADisabledAccountChangesAndLogsNothing() {
        User target = user(TARGET, "alice@example.com", Role.USER);
        Instant earlier = NOW.minusSeconds(3600);
        target.disable(earlier);
        locked(user(ACTOR, "admin@example.com", Role.ADMIN), target);

        assertThat(service().disable(ME, TARGET).disabled()).isTrue();

        assertThat(target.getDisabledAt()).isEqualTo(earlier);
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(rememberMeTokens, emailTokens);
        assertThat(capture.lines()).isEmpty();
    }

    @Test
    void enableClearsOnlyTheDisabledStateAndEnablingAnActiveAccountIsANoOp() {
        User target = user(TARGET, "alice@example.com", Role.USER);
        target.disable(NOW.minusSeconds(60));
        target.endSessionsBefore(NOW.minusSeconds(60));
        locked(user(ACTOR, "admin@example.com", Role.ADMIN), target);

        assertThat(service().enable(ME, TARGET).disabled()).isFalse();
        assertThat(target.getSessionsValidAfter()).isEqualTo(NOW.minusSeconds(60));
        verifyNoInteractions(rememberMeTokens, emailTokens);
        assertThat(audit("ADMIN_USER_ENABLED")).containsExactly("SECURITY event=ADMIN_USER_ENABLED actor_id=1"
                + " target_id=42 action=enable email=admin@example.com ip=- path=-");

        service().enable(ME, TARGET);
        verify(users, times(1)).saveAndFlush(target);
        assertThat(capture.lines()).hasSize(1);
    }

    @Test
    void revokeSessionsAlwaysEndsEverySessionAndLogs() {
        User target = user(TARGET, "alice@example.com", Role.ADMIN);
        locked(user(ACTOR, "admin@example.com", Role.SUPER_ADMIN), target);

        service().revokeSessions(ME, TARGET);
        service().revokeSessions(ME, TARGET);

        assertThat(target.getSessionsValidAfter()).isEqualTo(NOW);
        assertThat(target.isDisabled()).isFalse();
        verify(rememberMeTokens, times(2)).deleteAllForUser(TARGET);
        assertThat(audit("ADMIN_USER_SESSIONS_REVOKED")).hasSize(2);
    }

    @Test
    void roleChangeLogsFromAndToAndTheSameRoleIsANoOp() {
        User target = user(TARGET, "alice@example.com", Role.USER);
        locked(user(ACTOR, "admin@example.com", Role.SUPER_ADMIN), target);

        assertThat(service().changeRole(ME, TARGET, new ChangeRoleRequest("ADMIN")).role()).isEqualTo(Role.ADMIN);
        assertThat(service().changeRole(ME, TARGET, new ChangeRoleRequest("ADMIN")).role()).isEqualTo(Role.ADMIN);
        assertThat(service().changeRole(ME, TARGET, new ChangeRoleRequest("USER")).role()).isEqualTo(Role.USER);

        assertThat(capture.lines()).containsExactly(
                "SECURITY event=ADMIN_ROLE_CHANGED actor_id=1 target_id=42 action=change_role from=USER to=ADMIN"
                        + " email=admin@example.com ip=- path=-",
                "SECURITY event=ADMIN_ROLE_CHANGED actor_id=1 target_id=42 action=change_role from=ADMIN to=USER"
                        + " email=admin@example.com ip=- path=-");
        verify(users, times(2)).saveAndFlush(target);
    }

    @Test
    void roleOtherThanUserOrAdminIs400BeforeTheLimitAndTheDatabase() {
        limiter = mock(AdminRateLimiter.class);
        for (String role : new String[] {"SUPER_ADMIN", "admin", "OWNER", "", null}) {
            assertThatThrownBy(() -> service().changeRole(ME, TARGET, new ChangeRoleRequest(role)))
                    .as(String.valueOf(role))
                    .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                        assertThat(e.bindingResult().getFieldError("role").getCode()).isEqualTo("InvalidRole");
                        assertThat(e.bindingResult().getFieldError("role").getDefaultMessage())
                                .isEqualTo("Role must be USER or ADMIN");
                    });
        }
        verifyNoInteractions(limiter, users);
    }

    @Test
    void deleteRemovesRememberMeLoginsThenUsesTheSharedAccountDelete() {
        User target = user(TARGET, "alice@example.com", Role.USER);
        locked(user(ACTOR, "admin@example.com", Role.ADMIN), target);

        service().delete(ME, TARGET);

        InOrder order = inOrder(rememberMeTokens, accounts);
        order.verify(rememberMeTokens).deleteAllForUser(TARGET);
        order.verify(accounts).deleteNow(target);
        assertThat(capture.lines()).containsExactly("SECURITY event=ADMIN_USER_DELETED actor_id=1 target_id=42"
                + " action=delete email=admin@example.com ip=- path=-");
    }

    @Test
    void aLostLockConflictIsRetriedOnce() {
        User target = user(TARGET, "alice@example.com", Role.USER);
        when(users.findAllByIdForUpdate(List.of(ACTOR, TARGET)))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(List.of(user(ACTOR, "admin@example.com", Role.ADMIN), target));

        service().disable(ME, TARGET);

        verify(users, times(2)).findAllByIdForUpdate(List.of(ACTOR, TARGET));
        assertThat(audit("ADMIN_USER_DISABLED")).hasSize(1);
    }

    /** 0.13.0: an account deletion that loses deadlocks is run again (DeadlockRetry), then the usual extra retry. */
    @Test
    void aDeleteThatLosesDeadlocksIsRunAgain() {
        User target = user(TARGET, "alice@example.com", Role.USER);
        CannotAcquireLockException deadlock = new CannotAcquireLockException("deadlock",
                new java.sql.SQLException("Deadlock found", "40001", 1213));
        when(users.findAllByIdForUpdate(List.of(ACTOR, TARGET)))
                .thenThrow(deadlock, deadlock)
                .thenReturn(List.of(user(ACTOR, "admin@example.com", Role.ADMIN), target));

        service().delete(ME, TARGET);

        verify(users, times(3)).findAllByIdForUpdate(List.of(ACTOR, TARGET));
        verify(accounts).deleteNow(target);
        assertThat(audit("ADMIN_USER_DELETED")).hasSize(1);
    }

    // ---- rate limit ----

    @Test
    void everyChangeRequestCountsIncludingRefusedOnesAndTheLimitComesBeforeAnyCheck() {
        limiter = new AdminRateLimiter(new AdminRateLimitProperties(2, 1000), Clock.systemUTC());
        AdminUserService service = service();

        assertThatThrownBy(() -> service.disable(ME, ACTOR)).isInstanceOf(CannotModifySelfException.class);
        assertThatThrownBy(() -> service.delete(ME, ACTOR)).isInstanceOf(CannotModifySelfException.class);
        assertThatThrownBy(() -> service.enable(ME, ACTOR))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.limit()).isEqualTo(RateLimitedException.Limit.ADMIN_ACTION_USER);
                    assertThat(e.email()).isEqualTo("admin@example.com");
                });

        // Another admin is not affected.
        AuthenticatedUser other = new AuthenticatedUser(7L, "other@example.com", "Other");
        assertThatThrownBy(() -> service.disable(other, 7L)).isInstanceOf(CannotModifySelfException.class);
        verifyNoInteractions(users);
    }

    @Test
    void theListIsNotLimited() {
        limiter = mock(AdminRateLimiter.class);
        when(users.search(any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service().list(AdminUserQuery.of(null, null, null));

        verifyNoInteractions(limiter);
    }

    // ---- list ----

    @Test
    void listSearchesTrimmedEscapedTextNewestFirstAndMarksGoogleAccounts() {
        User alice = user(5L, "alice@example.com", Role.ADMIN);
        User bob = user(4L, "bob@example.com", Role.USER);
        bob.disable(NOW);
        when(users.search(eq("%a!%b!_c!!%"), any())).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(1);
            return new PageImpl<>(List.of(alice, bob), pageable, 7);
        });
        when(connections.findUserIdsWithProvider(UserConnection.GOOGLE, List.of(5L, 4L))).thenReturn(List.of(4L));

        AdminUserPage page = service().list(AdminUserQuery.of("  a%b_c!  ", 1, 2));

        verify(users).search(eq("%a!%b!_c!!%"),
                eq(PageRequest.of(1, 2, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))));
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(7);
        assertThat(page.totalPages()).isEqualTo(4);
        assertThat(page.content()).extracting(AdminUserResponse::id).containsExactly(5L, 4L);
        assertThat(page.content()).extracting(AdminUserResponse::googleConnected).containsExactly(false, true);
        assertThat(page.content()).extracting(AdminUserResponse::disabled).containsExactly(false, true);
        assertThat(page.content()).extracting(AdminUserResponse::role).containsExactly(Role.ADMIN, Role.USER);
    }

    @Test
    void blankSearchMeansEveryoneAndAnEmptyPageSkipsTheConnectionLookup() {
        when(users.search(any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        AdminUserPage page = service().list(AdminUserQuery.of("   ", null, null));

        verify(users).search(eq(null), any());
        verify(connections, never()).findUserIdsWithProvider(any(), anyCollection());
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.size()).isEqualTo(20);
    }

    @Test
    void listValidatesSearchLengthPageAndSize() {
        assertThatThrownBy(() -> service().list(AdminUserQuery.of("  " + "q".repeat(101) + " ", -1, 101)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.bindingResult().getFieldError("q").getCode()).isEqualTo("Size");
                    assertThat(e.bindingResult().getFieldError("page").getCode()).isEqualTo("Min");
                    assertThat(e.bindingResult().getFieldError("size").getCode()).isEqualTo("Max");
                });
        assertThatThrownBy(() -> service().list(AdminUserQuery.of(null, 0, 0)))
                .isInstanceOf(InvalidFieldsException.class);
        verifyNoInteractions(users);

        // 100 characters after trimming is fine.
        when(users.search(any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));
        service().list(AdminUserQuery.of(" " + "q".repeat(100) + " ", 0, 100));
        verify(users).search(any(), any());
        verify(connections, never()).findByUserIdAndProvider(anyLong(), any());
    }
}
