import { Routes } from '@angular/router';
import { adminGuard, authGuard, guestGuard } from './core/auth/auth.guards';
import { AccountPage } from './features/account/account-page/account-page';
import { AdminUsersPage } from './features/admin/admin-users-page/admin-users-page';
import { ForgotPasswordPage } from './features/auth/forgot-password/forgot-password-page';
import { LoginPage } from './features/auth/login/login-page';
import { RegisterPage } from './features/auth/register/register-page';
import { ResetPasswordPage } from './features/auth/reset-password/reset-password-page';
import { VerifyEmailPage } from './features/auth/verify-email/verify-email-page';
import { TasksPage } from './features/tasks/tasks-page/tasks-page';
import { ShellLayout } from './layout/shell/shell-layout';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'tasks' },
  { path: 'login', title: 'Sign in · Task App', component: LoginPage, canActivate: [guestGuard] },
  {
    path: 'register',
    title: 'Create account · Task App',
    component: RegisterPage,
    canActivate: [guestGuard],
  },
  // Email link pages: public, signed in or not (no guard, outside the shell).
  { path: 'verify-email', title: 'Confirm email · Task App', component: VerifyEmailPage },
  {
    path: 'forgot-password',
    title: 'Forgot password · Task App',
    component: ForgotPasswordPage,
  },
  { path: 'reset-password', title: 'Reset password · Task App', component: ResetPasswordPage },
  {
    path: '',
    component: ShellLayout,
    canActivate: [authGuard],
    children: [
      { path: 'tasks', title: 'Tasks · Task App', component: TasksPage },
      // One of my lists (T3a). "My tasks" stays at /tasks; same page component.
      { path: 'lists/:id', title: 'Tasks · Task App', component: TasksPage },
      { path: 'account', title: 'Account · Task App', component: AccountPage },
      {
        path: 'admin/users',
        title: 'Users · Task App',
        component: AdminUsersPage,
        canActivate: [authGuard, adminGuard],
      },
    ],
  },
  { path: '**', redirectTo: 'tasks' },
];
