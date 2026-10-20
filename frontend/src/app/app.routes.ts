import { Routes } from '@angular/router';
import { adminGuard, authGuard, guestGuard } from './core/auth/auth.guards';
import { LoginPage } from './features/auth/login/login-page';
import { TasksPage } from './features/tasks/tasks-page/tasks-page';
import { ShellLayout } from './layout/shell/shell-layout';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'tasks' },
  { path: 'login', title: 'Sign in · Task App', component: LoginPage, canActivate: [guestGuard] },
  {
    path: 'register',
    title: 'Create account · Task App',
    loadComponent: () =>
      import('./features/auth/register/register-page').then((m) => m.RegisterPage),
    canActivate: [guestGuard],
  },
  // Email link pages: public, signed in or not (no guard, outside the shell).
  {
    path: 'verify-email',
    title: 'Confirm email · Task App',
    loadComponent: () =>
      import('./features/auth/verify-email/verify-email-page').then((m) => m.VerifyEmailPage),
  },
  {
    path: 'forgot-password',
    title: 'Forgot password · Task App',
    loadComponent: () =>
      import('./features/auth/forgot-password/forgot-password-page').then(
        (m) => m.ForgotPasswordPage,
      ),
  },
  {
    path: 'reset-password',
    title: 'Reset password · Task App',
    loadComponent: () =>
      import('./features/auth/reset-password/reset-password-page').then((m) => m.ResetPasswordPage),
  },
  {
    path: '',
    component: ShellLayout,
    canActivate: [authGuard],
    children: [
      { path: 'tasks', title: 'Tasks · Task App', component: TasksPage },
      // One of my lists (T3a). "My tasks" stays at /tasks; same page component.
      { path: 'lists/:id', title: 'Tasks · Task App', component: TasksPage },
      // Invitations I received (T3b). The emailed link points here; see authGuard.
      {
        path: 'invitations',
        title: 'Invitations · Task App',
        loadComponent: () =>
          import('./features/sharing/invitations-page/invitations-page').then(
            (m) => m.InvitationsPage,
          ),
      },
      {
        path: 'account',
        title: 'Account · Task App',
        loadComponent: () =>
          import('./features/account/account-page/account-page').then((m) => m.AccountPage),
      },
      {
        path: 'admin/users',
        title: 'Users · Task App',
        loadComponent: () =>
          import('./features/admin/admin-users-page/admin-users-page').then(
            (m) => m.AdminUsersPage,
          ),
        canActivate: [authGuard, adminGuard],
      },
    ],
  },
  { path: '**', redirectTo: 'tasks' },
];
