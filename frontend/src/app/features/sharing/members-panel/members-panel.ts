import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  OnInit,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize, Subscription } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { readProblem } from '../../../core/auth/problem';
import {
  applyServerFieldErrors,
  EMAIL_MAX,
  emailTrimmed,
  fieldMessage,
  fieldProblem,
  isRateLimited,
  maxTrimmed,
  RATE_LIMITED_MESSAGE,
  requiredTrimmed,
} from '../../auth/auth-form';
import { isListNotFound } from '../../lists/lists.logic';
import { TaskList } from '../../lists/lists.models';
import { ListsStore } from '../../lists/lists.store';
import { isUnauthenticated } from '../../tasks/task-form';
import {
  CANCEL_INVITE_ERROR,
  INVITATION_SENT_MESSAGE,
  INVITE_ERROR,
  inviteConflictMessage,
  isInvitationNotFound,
  isOwnerOnly,
  hasSharingProblem,
  MEMBERS_LOAD_ERROR,
  NO_PENDING_INVITATIONS,
  OWNER_ONLY_MESSAGE,
  ownerCannotLeaveMessage,
  PENDING_LOAD_ERROR,
  REMOVE_MEMBER_ERROR,
  formatDay,
} from '../sharing.logic';
import { CreateInvitationRequest, ListInvitation, Member } from '../sharing.models';
import { SharingService } from '../sharing.service';

interface InviteError {
  message: string;
  /** Null when sending again can't help (limits, duplicates, rate limit). */
  retry: (() => void) | null;
}

/** Adds or replaces one entry by id. */
function withEntry<T>(items: ReadonlyMap<number, T>, id: number, value: T | null): Map<number, T> {
  const next = new Map(items);
  if (value === null) {
    next.delete(id);
  } else {
    next.set(id, value);
  }
  return next;
}

/**
 * The members panel of a list (from the list header's "⋯" > Members). Every member sees the
 * members (name, email, role). The owner also invites by email, sees pending invitations
 * (with their expiry) and cancels them, and removes members. Nothing is live: it loads on open.
 */
@Component({
  selector: 'app-members-panel',
  imports: [ReactiveFormsModule],
  templateUrl: './members-panel.html',
  styleUrls: ['../../tasks/dialog.scss', './members-panel.scss'],
})
export class MembersPanel implements OnInit {
  private readonly api = inject(SharingService);
  private readonly store = inject(ListsStore);
  private readonly auth = inject(AuthService);
  private readonly subs = new Set<Subscription>();

  readonly list = input.required<TaskList>();

  readonly closed = output<void>();
  /** The list is gone or I'm no longer a member (404 `LIST_NOT_FOUND`). */
  readonly notFound = output<void>();

  /** Owner actions (invite, pending, remove); "My tasks" can't be shared. */
  protected readonly isOwner = computed(() => this.list().role === 'OWNER');
  protected readonly canInvite = computed(() => this.isOwner() && !this.list().personal);
  protected readonly myId = computed(() => this.auth.currentUser()?.id ?? null);

  protected readonly members = signal<readonly Member[] | null>(null);
  protected readonly membersLoading = signal(false);
  protected readonly membersFailed = signal(false);
  /** userId waiting for "Remove <name>?" confirmation. */
  protected readonly confirmingRemove = signal<number | null>(null);
  protected readonly removing = signal<ReadonlySet<number>>(new Set());
  protected readonly memberErrors = signal<ReadonlyMap<number, string>>(new Map());

  protected readonly pending = signal<readonly ListInvitation[] | null>(null);
  protected readonly pendingLoading = signal(false);
  protected readonly pendingFailed = signal(false);
  protected readonly cancelling = signal<ReadonlySet<number>>(new Set());
  protected readonly pendingErrors = signal<ReadonlyMap<number, string>>(new Map());

  protected readonly inviting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly inviteError = signal<InviteError | null>(null);
  protected readonly inviteSent = signal<string | null>(null);
  /** "Only the owner can do that" (my role changed since the page loaded). */
  protected readonly banner = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [requiredTrimmed, emailTrimmed, maxTrimmed(EMAIL_MAX)]],
  });

  protected readonly membersLoadError = MEMBERS_LOAD_ERROR;
  protected readonly pendingLoadError = PENDING_LOAD_ERROR;
  protected readonly noPending = NO_PENDING_INVITATIONS;

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
    inject(DestroyRef).onDestroy(() => this.subs.forEach((s) => s.unsubscribe()));
  }

  ngOnInit(): void {
    this.loadMembers();
    if (this.canInvite()) {
      this.loadPending();
    }
  }

  protected onCancel(event: Event): void {
    event.preventDefault();
    this.close();
  }

  protected close(): void {
    this.closed.emit();
  }

  /** "Nov 2, 2026". */
  protected day(instant: string): string {
    return formatDay(instant);
  }

  protected roleLabel(member: Member): string {
    return member.role === 'OWNER' ? 'Owner' : 'Member';
  }

  protected emailError(): string | null {
    const control = this.form.controls.email;
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return fieldMessage('email', control);
  }

  protected loadMembers(quiet = false): void {
    this.membersFailed.set(false);
    this.membersLoading.set(!quiet || this.members() === null);
    this.track(
      this.api.members(this.list().id).subscribe({
        next: (members) => {
          this.membersLoading.set(false);
          this.members.set(members);
        },
        error: (error: unknown) => {
          this.membersLoading.set(false);
          if (!this.handleCommon(error)) {
            this.membersFailed.set(true);
          }
        },
      }),
    );
  }

  protected loadPending(quiet = false): void {
    this.pendingFailed.set(false);
    this.pendingLoading.set(!quiet || this.pending() === null);
    this.track(
      this.api.listInvitations(this.list().id).subscribe({
        next: (invitations) => {
          this.pendingLoading.set(false);
          this.pending.set(invitations);
        },
        error: (error: unknown) => {
          this.pendingLoading.set(false);
          if (!this.handleCommon(error)) {
            this.pendingFailed.set(true);
          }
        },
      }),
    );
  }

  protected askRemove(member: Member): void {
    this.memberErrors.update((e) => withEntry(e, member.userId, null));
    this.confirmingRemove.set(member.userId);
  }

  protected cancelRemove(): void {
    this.confirmingRemove.set(null);
  }

  protected remove(member: Member): void {
    const id = member.userId;
    if (this.removing().has(id)) {
      return;
    }
    this.banner.set(null);
    this.memberErrors.update((e) => withEntry(e, id, null));
    this.removing.update((s) => new Set([...s, id]));
    this.track(
      this.api
        .removeMember(this.list().id, id)
        .pipe(finalize(() => this.removing.update((s) => new Set([...s].filter((x) => x !== id)))))
        .subscribe({
          next: () => {
            this.confirmingRemove.set(null);
            this.members.update((m) => m?.filter((x) => x.userId !== id) ?? null);
            this.store.refresh();
          },
          error: (error: unknown) => {
            if (this.handleCommon(error)) {
              return;
            }
            if (hasSharingProblem(error, 404, 'MEMBER_NOT_FOUND')) {
              // Already removed or left: show the current members.
              this.confirmingRemove.set(null);
              this.members.update((m) => m?.filter((x) => x.userId !== id) ?? null);
              this.store.refresh();
              this.loadMembers(true);
              return;
            }
            this.memberErrors.update((e) =>
              withEntry(e, id, ownerCannotLeaveMessage(error) ?? REMOVE_MEMBER_ERROR),
            );
          },
        }),
    );
  }

  protected submitInvite(): void {
    if (this.inviting()) {
      return;
    }
    this.submitted.set(true);
    this.inviteSent.set(null);
    this.inviteError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.sendInvite({ email: this.form.controls.email.value.trim() });
  }

  protected cancelInvitation(invitation: ListInvitation): void {
    const id = invitation.id;
    if (this.cancelling().has(id)) {
      return;
    }
    this.banner.set(null);
    this.pendingErrors.update((e) => withEntry(e, id, null));
    this.cancelling.update((s) => new Set([...s, id]));
    this.track(
      this.api
        .cancelInvitation(this.list().id, id)
        .pipe(
          finalize(() => this.cancelling.update((s) => new Set([...s].filter((x) => x !== id)))),
        )
        .subscribe({
          next: () => this.pending.update((p) => p?.filter((x) => x.id !== id) ?? null),
          error: (error: unknown) => {
            if (this.handleCommon(error)) {
              return;
            }
            if (isInvitationNotFound(error)) {
              // Already accepted, declined or cancelled: show the current pending list.
              this.pending.update((p) => p?.filter((x) => x.id !== id) ?? null);
              this.loadPending(true);
              return;
            }
            this.pendingErrors.update((e) => withEntry(e, id, CANCEL_INVITE_ERROR));
          },
        }),
    );
  }

  private sendInvite(request: CreateInvitationRequest): void {
    if (this.inviting()) {
      return;
    }
    this.banner.set(null);
    this.inviteError.set(null);
    this.inviteSent.set(null);
    this.inviting.set(true);
    this.track(
      this.api
        .invite(this.list().id, request)
        .pipe(finalize(() => this.inviting.set(false)))
        .subscribe({
          next: (invitation) => {
            // An expired invitation sent again keeps its id: replace it.
            this.pending.update((p) => [
              invitation,
              ...(p ?? []).filter((x) => x.id !== invitation.id),
            ]);
            this.inviteSent.set(INVITATION_SENT_MESSAGE);
            this.form.reset();
            this.submitted.set(false);
          },
          error: (error: unknown) => {
            if (this.handleCommon(error)) {
              return;
            }
            const problem = fieldProblem(error);
            if (problem && applyServerFieldErrors(problem, { email: this.form.controls.email })) {
              return;
            }
            const conflict = inviteConflictMessage(error);
            if (conflict) {
              this.inviteError.set({ message: conflict, retry: null });
              return;
            }
            if (isRateLimited(error)) {
              this.inviteError.set({
                message: readProblem(error)?.detail || RATE_LIMITED_MESSAGE,
                retry: null,
              });
              return;
            }
            this.inviteError.set({ message: INVITE_ERROR, retry: () => this.sendInvite(request) });
          },
        }),
    );
  }

  /**
   * Errors every call treats the same: 401 (handled globally), 404 `LIST_NOT_FOUND` (the panel
   * closes and the page shows "List not found") and 403 `OWNER_ONLY` (banner + refetch my role).
   * Returns true if handled.
   */
  private handleCommon(error: unknown): boolean {
    if (isUnauthenticated(error)) {
      return true;
    }
    if (isListNotFound(error)) {
      this.notFound.emit();
      return true;
    }
    if (isOwnerOnly(error)) {
      this.banner.set(OWNER_ONLY_MESSAGE);
      this.confirmingRemove.set(null);
      this.store.refresh();
      return true;
    }
    return false;
  }

  private track(sub: Subscription): void {
    this.subs.add(sub);
    sub.add(() => this.subs.delete(sub));
  }
}
