import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guards';
import { LoginPage } from './features/auth/login/login-page';
import { RegisterPage } from './features/auth/register/register-page';
import { TasksPlaceholderPage } from './features/tasks/tasks-placeholder-page';
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
  {
    path: '',
    component: ShellLayout,
    canActivate: [authGuard],
    children: [{ path: 'tasks', title: 'Tasks · Task App', component: TasksPlaceholderPage }],
  },
  { path: '**', redirectTo: 'tasks' },
];
