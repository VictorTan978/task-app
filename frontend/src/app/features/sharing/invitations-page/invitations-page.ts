import { Component, computed, DestroyRef, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { map, Observable, Subscription } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { listLink } from '../../lists/list-picker/list-picker';
import { TaskList } from '../../lists/lists.models';
import { ListsStore } from '../../lists/lists.store';
import { isUnauthenticated } from '../../tasks/task-form';
import { InvitationsStore } from '../invitations.store';
import {
  ACCEPT_ERROR,
  CONFIRM_EMAIL_FOR_INVITATIONS,
  DECLINE_ERROR,
  INVITATION_INVALID_MESSAGE,
  INVITATIONS_LOAD_ERROR,
  isInvitationNotFound,
  memberLimitMessage,
  NO_PENDING_INVITATIONS,
  formatDay,
} from '../sharing.logic';
import { MyInvitation } from '../sharing.models';
import { SharingService } from '../sharing.service';

type RowAction = 'accept' | 'decline';

/**
 * `/invitations`: the invitations I received, with Accept and Decline. Accepting adds the list
 * to the picker and shows "Joined <list>. Open list". A stale invitation (cancelled, expired,
 * its list deleted) shows "This invitation is no longer valid" and the page reloads. An
 * unverified email sees a "confirm your email" notice instead (the API lists nothing then).
 */
@Component({
  selector: 'app-invitations-page',
  imports: [RouterLink],
  templateUrl: './invitations-page.html',
  styleUrl: './invitations-page.scss',
})
export class InvitationsPage {
  private readonly api = inject(SharingService);
  private readonly auth = inject(AuthService);
  private readonly lists = inject(ListsStore);
  private readonly badge = inject(InvitationsStore);
  private loadSub: Subscription | null = null;
  private readonly rowSubs = new Map<number, Subscription>();

  protected readonly emailVerified = computed(
    () => this.auth.currentUser()?.emailVerified ?? false,
  );

  /** Null until the first successful load. */
  protected readonly invitations = signal<readonly MyInvitation[] | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);
  /** Lists joined on this page (newest first), shown as "Joined <list>. Open list". */
  protected readonly joined = signal<readonly TaskList[]>([]);
  /** Page notice, e.g. "This invitation is no longer valid". */
  protected readonly notice = signal<string | null>(null);
  /** Running action per invitation id. */
  protected readonly busy = signal<ReadonlyMap<number, RowAction>>(new Map());
  /** Error per invitation id (the buttons stay, so pressing again retries). */
  protected readonly rowErrors = signal<ReadonlyMap<number, string>>(new Map());

  protected readonly loadError = INVITATIONS_LOAD_ERROR;
  protected readonly emptyMessage = NO_PENDING_INVITATIONS;
  protected readonly confirmEmailMessage = CONFIRM_EMAIL_FOR_INVITATIONS;
  protected readonly isEmpty = computed(() => this.invitations()?.length === 0);

  constructor() {
    // Load once the email is verified (now, or after the user is re-read on a later page).
    effect(() => {
      if (this.emailVerified()) {
        untracked(() => this.load());
      }
    });
    inject(DestroyRef).onDestroy(() => {
      this.loadSub?.unsubscribe();
      this.rowSubs.forEach((sub) => sub.unsubscribe());
    });
  }

  /** "Nov 2, 2026". */
  protected day(instant: string): string {
    return formatDay(instant);
  }

  protected link(list: TaskList): string {
    return listLink(list);
  }

  protected busyWith(id: number): RowAction | undefined {
    return this.busy().get(id);
  }

  protected retryLoad(): void {
    this.load();
  }

  protected accept(invitation: MyInvitation): void {
    this.run(invitation, 'accept');
  }

  protected decline(invitation: MyInvitation): void {
    this.run(invitation, 'decline');
  }

  /** Loads my invitations; `quiet` keeps the current rows shown (no spinner) while reloading. */
  private load(quiet = false): void {
    this.loadSub?.unsubscribe();
    this.loadFailed.set(false);
    this.loading.set(!quiet || this.invitations() === null);
    this.loadSub = this.api.myInvitations().subscribe({
      next: (invitations) => {
        this.loading.set(false);
        this.invitations.set(invitations);
        this.badge.set(invitations.length);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        if (!isUnauthenticated(error)) {
          this.loadFailed.set(true);
        }
      },
    });
  }

  private run(invitation: MyInvitation, action: RowAction): void {
    const id = invitation.id;
    if (this.busy().has(id)) {
      return;
    }
    this.notice.set(null);
    this.setRowError(id, null);
    this.setBusy(id, action);
    const request: Observable<TaskList | null> =
      action === 'accept' ? this.api.accept(id) : this.api.decline(id).pipe(map(() => null));
    const sub = request.subscribe({
      next: (result) => {
        this.setBusy(id, null);
        this.drop(id);
        if (action === 'accept' && result) {
          this.lists.upsert(result);
          this.lists.refresh();
          this.joined.update((joined) => [result, ...joined.filter((l) => l.id !== result.id)]);
        }
      },
      error: (error: unknown) => {
        this.setBusy(id, null);
        if (isUnauthenticated(error)) {
          return;
        }
        if (isInvitationNotFound(error)) {
          this.drop(id);
          this.notice.set(INVITATION_INVALID_MESSAGE);
          this.load(true);
          return;
        }
        const limit = action === 'accept' ? memberLimitMessage(error) : null;
        this.setRowError(id, limit ?? (action === 'accept' ? ACCEPT_ERROR : DECLINE_ERROR));
      },
    });
    this.rowSubs.set(id, sub);
  }

  /** Removes a row and lowers the badge. */
  private drop(id: number): void {
    const current = this.invitations();
    if (current === null) {
      return;
    }
    const next = current.filter((i) => i.id !== id);
    this.invitations.set(next);
    this.badge.set(next.length);
  }

  private setBusy(id: number, action: RowAction | null): void {
    this.busy.update((busy) => {
      const next = new Map(busy);
      if (action) {
        next.set(id, action);
      } else {
        next.delete(id);
        this.rowSubs.delete(id);
      }
      return next;
    });
  }

  private setRowError(id: number, message: string | null): void {
    this.rowErrors.update((errors) => {
      const next = new Map(errors);
      if (message) {
        next.set(id, message);
      } else {
        next.delete(id);
      }
      return next;
    });
  }
}
