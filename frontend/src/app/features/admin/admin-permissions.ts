import { Role } from '../../core/auth/auth.models';
import { AdminUser } from './admin.models';

/** What an admin can do to one account from the admin page. */
export type AdminAction =
  'makeAdmin' | 'removeAdmin' | 'revokeSessions' | 'disable' | 'enable' | 'delete';

/** The signed-in admin, as far as the permission rules care. */
export interface Actor {
  id: number;
  role: Role;
}

export function isAdminRole(role: Role | undefined | null): boolean {
  return role === 'ADMIN' || role === 'SUPER_ADMIN';
}

/**
 * Actions `actor` may do on `target` (spec "Roles" table), in menu order. Empty means no actions
 * menu. Convenience only: the backend enforces the same rules.
 * - Nobody acts on themselves or on a super admin.
 * - Admins act on normal users only; super admins also on admins.
 * - Only super admins change roles (USER <-> ADMIN).
 */
export function allowedActions(actor: Actor | null, target: AdminUser): AdminAction[] {
  if (!actor || !isAdminRole(actor.role) || actor.id === target.id) {
    return [];
  }
  if (target.role === 'SUPER_ADMIN') {
    return [];
  }
  const superAdmin = actor.role === 'SUPER_ADMIN';
  if (target.role === 'ADMIN' && !superAdmin) {
    return [];
  }
  const actions: AdminAction[] = [];
  if (superAdmin) {
    actions.push(target.role === 'ADMIN' ? 'removeAdmin' : 'makeAdmin');
  }
  actions.push('revokeSessions', target.disabled ? 'enable' : 'disable', 'delete');
  return actions;
}

/** Destructive actions are shown in red. */
export function isDestructive(action: AdminAction): boolean {
  return action === 'disable' || action === 'delete';
}

export const ROLE_LABELS: Record<Role, string> = {
  SUPER_ADMIN: 'Super admin',
  ADMIN: 'Admin',
  USER: 'User',
};

export const ACTION_LABELS: Record<AdminAction, string> = {
  makeAdmin: 'Make admin',
  removeAdmin: 'Remove admin',
  revokeSessions: 'Sign out everywhere',
  disable: 'Disable',
  enable: 'Enable',
  delete: 'Delete',
};

/** Success notice shown above the table after each action. */
export const ACTION_NOTICES: Record<AdminAction, string> = {
  makeAdmin: 'Role changed',
  removeAdmin: 'Role changed',
  revokeSessions: 'User signed out everywhere',
  disable: 'User disabled',
  enable: 'User enabled',
  delete: 'User deleted',
};

/** Delete confirmation: the typed email must match, ignoring surrounding spaces and case. */
export function emailMatches(typed: string, email: string): boolean {
  return typed.trim().toLowerCase() === email.trim().toLowerCase();
}
