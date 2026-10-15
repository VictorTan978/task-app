import { Role } from '../../core/auth/auth.models';
import {
  Actor,
  allowedActions,
  emailMatches,
  isAdminRole,
  isDestructive,
} from './admin-permissions';
import { AdminUser } from './admin.models';

function target(role: Role, overrides: Partial<AdminUser> = {}): AdminUser {
  return {
    id: 2,
    email: 'alice@example.com',
    displayName: 'Alice',
    createdAt: '2026-10-01T10:00:00Z',
    emailVerified: true,
    googleConnected: false,
    role,
    disabled: false,
    ...overrides,
  };
}

const ADMIN: Actor = { id: 1, role: 'ADMIN' };
const SUPER: Actor = { id: 1, role: 'SUPER_ADMIN' };

describe('allowedActions (spec Roles table)', () => {
  it('admin on an active USER: sign out, disable, delete (no role change)', () => {
    expect(allowedActions(ADMIN, target('USER'))).toEqual(['revokeSessions', 'disable', 'delete']);
  });

  it('admin on a disabled USER: enable instead of disable', () => {
    expect(allowedActions(ADMIN, target('USER', { disabled: true }))).toEqual([
      'revokeSessions',
      'enable',
      'delete',
    ]);
  });

  it('super admin on a USER: also "Make admin"', () => {
    expect(allowedActions(SUPER, target('USER'))).toEqual([
      'makeAdmin',
      'revokeSessions',
      'disable',
      'delete',
    ]);
  });

  it('super admin on an ADMIN: all actions incl. "Remove admin"', () => {
    expect(allowedActions(SUPER, target('ADMIN', { disabled: true }))).toEqual([
      'removeAdmin',
      'revokeSessions',
      'enable',
      'delete',
    ]);
  });

  it('admin on an ADMIN: nothing', () => {
    expect(allowedActions(ADMIN, target('ADMIN'))).toEqual([]);
  });

  it.each([ADMIN, SUPER])('nobody acts on a SUPER_ADMIN (%o)', (actor) => {
    expect(allowedActions(actor, target('SUPER_ADMIN'))).toEqual([]);
  });

  it.each([ADMIN, SUPER])('nobody acts on themselves (%o)', (actor) => {
    expect(allowedActions(actor, target('USER', { id: actor.id }))).toEqual([]);
  });

  it('a USER or no actor gets nothing', () => {
    expect(allowedActions({ id: 1, role: 'USER' }, target('USER'))).toEqual([]);
    expect(allowedActions(null, target('USER'))).toEqual([]);
  });
});

describe('admin permission helpers', () => {
  it('isAdminRole', () => {
    expect(isAdminRole('ADMIN')).toBe(true);
    expect(isAdminRole('SUPER_ADMIN')).toBe(true);
    expect(isAdminRole('USER')).toBe(false);
    expect(isAdminRole(null)).toBe(false);
  });

  it('only disable and delete are destructive', () => {
    expect(isDestructive('disable')).toBe(true);
    expect(isDestructive('delete')).toBe(true);
    expect(isDestructive('enable')).toBe(false);
    expect(isDestructive('revokeSessions')).toBe(false);
    expect(isDestructive('makeAdmin')).toBe(false);
    expect(isDestructive('removeAdmin')).toBe(false);
  });

  it.each([
    ['alice@example.com', true],
    ['  Alice@Example.COM ', true],
    ['alice@example.co', false],
    ['', false],
  ])('emailMatches("%s") -> %s', (typed, expected) => {
    expect(emailMatches(typed, 'alice@example.com')).toBe(expected);
  });
});
