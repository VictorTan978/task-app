import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, Subject, Subscription } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { hasProblemCode } from '../../../core/auth/problem';
import { AdminActionDialog } from '../admin-action-dialog/admin-action-dialog';
import { LOAD_USERS_ERROR } from '../admin-errors';
import {
  ACTION_LABELS,
  ACTION_NOTICES,
  Actor,
  AdminAction,
  allowedActions,
  isDestructive,
  ROLE_LABELS,
} from '../admin-permissions';
import { AdminUser, AdminUserPage } from '../admin.models';
import { ADMIN_QUERY_MAX, AdminService } from '../admin.service';

/** Delay between the last keystroke in the search box and the request. */
export const SEARCH_DEBOUNCE_MS = 300;

interface Row {
  user: AdminUser;
  you: boolean;
  actions: AdminAction[];
}

interface OpenDialog {
  user: AdminUser;
  action: AdminAction;
}

/** `/admin/users`: all accounts, 20 per page, newest first, with per-row admin actions. */
@Component({
  selector: 'app-admin-users-page',
  imports: [DatePipe, RouterLink, AdminActionDialog],
  templateUrl: './admin-users-page.html',
  styleUrl: './admin-users-page.scss',
  host: {
    '(document:click)': 'onDocumentClick($event)',
    '(document:keydown.escape)': 'closeMenu()',
  },
})
export class AdminUsersPage {
  private readonly admin = inject(AdminService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly searches = new Subject<string>();
  private loadSub: Subscription | null = null;

  protected readonly queryMax = ADMIN_QUERY_MAX;
  protected readonly roleLabels = ROLE_LABELS;
  protected readonly actionLabels = ACTION_LABELS;
  protected readonly loadError = LOAD_USERS_ERROR;
  protected readonly isDestructive = isDestructive;

  /** Search applied to the list (trimmed). */
  private readonly query = signal('');
  /** Zero-based page shown or being loaded. */
  private readonly page = signal(0);
  protected readonly result = signal<AdminUserPage | null>(null);
  protected readonly loading = signal(false);
  protected readonly failed = signal(false);
  protected readonly notice = signal<string | null>(null);
  /** Id of the row whose actions menu is open. */
  protected readonly menuFor = signal<number | null>(null);
  protected readonly dialog = signal<OpenDialog | null>(null);

  private readonly actor = computed<Actor | null>(() => {
    const user = this.auth.currentUser();
    return user ? { id: user.id, role: user.role } : null;
  });

  protected readonly rows = computed<Row[]>(() => {
    const actor = this.actor();
    return (this.result()?.content ?? []).map((user) => ({
      user,
      you: actor?.id === user.id,
      actions: allowedActions(actor, user),
    }));
  });

  protected readonly pageNumber = computed(() => (this.result()?.page ?? 0) + 1);
  protected readonly totalPages = computed(() => this.result()?.totalPages ?? 0);
  protected readonly hasPrevious = computed(() => this.pageNumber() > 1);
  protected readonly hasNext = computed(() => this.pageNumber() < this.totalPages());

  constructor() {
    this.searches
      .pipe(
        debounceTime(SEARCH_DEBOUNCE_MS),
        map((value) => value.trim()),
        distinctUntilChanged(),
        takeUntilDestroyed(),
      )
      .subscribe((query) => {
        this.query.set(query);
        this.page.set(0);
        this.notice.set(null);
        this.load();
      });
    inject(DestroyRef).onDestroy(() => this.loadSub?.unsubscribe());
    this.load();
  }

  protected onSearch(event: Event): void {
    this.searches.next((event.target as HTMLInputElement).value);
  }

  protected load(): void {
    this.loadSub?.unsubscribe();
    this.loading.set(true);
    this.failed.set(false);
    this.closeMenu();
    this.loadSub = this.admin.list(this.query(), this.page()).subscribe({
      next: (result) => {
        // Past the end (e.g. the last user of the last page was deleted): show the last page.
        if (result.content.length === 0 && result.page > 0 && result.totalPages > 0) {
          this.page.set(result.totalPages - 1);
          this.load();
          return;
        }
        this.loading.set(false);
        this.page.set(result.page);
        this.result.set(result);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        if (error instanceof HttpErrorResponse && error.status === 401) {
          return; // The interceptor signs the tab out and goes to /login.
        }
        if (hasProblemCode(error, 403, 'FORBIDDEN')) {
          this.lostAdmin();
          return;
        }
        this.failed.set(true);
      },
    });
  }

  protected previous(): void {
    if (this.hasPrevious() && !this.loading()) {
      this.page.set(this.pageNumber() - 2);
      this.load();
    }
  }

  protected next(): void {
    if (this.hasNext() && !this.loading()) {
      this.page.set(this.pageNumber());
      this.load();
    }
  }

  protected toggleMenu(id: number): void {
    this.menuFor.update((open) => (open === id ? null : id));
  }

  protected closeMenu(): void {
    this.menuFor.set(null);
  }

  /** Clicks outside any actions menu close the open one. */
  protected onDocumentClick(event: Event): void {
    const target = event.target as Element | null;
    if (!target?.closest?.('[data-admin-menu]')) {
      this.closeMenu();
    }
  }

  protected openDialog(user: AdminUser, action: AdminAction): void {
    this.closeMenu();
    this.notice.set(null);
    this.dialog.set({ user, action });
  }

  protected onDone(action: AdminAction): void {
    this.dialog.set(null);
    this.notice.set(ACTION_NOTICES[action]);
    this.load();
  }

  /** 403 FORBIDDEN: no longer an admin. Re-read the user and leave the admin page. */
  protected lostAdmin(): void {
    this.dialog.set(null);
    this.auth.syncSession().subscribe(() => {
      void this.router.navigateByUrl(this.auth.isSignedIn() ? '/tasks' : '/login');
    });
  }
}
