import { HttpErrorResponse } from '@angular/common/http';
import {
  formatDay,
  hasSharingProblem,
  invitationsLabel,
  inviteConflictMessage,
  isInvitationNotFound,
  isOwnerOnly,
  memberCountLabel,
  memberLimitMessage,
  ownerCannotLeaveMessage,
} from './sharing.logic';

function httpError(status: number, code?: string, detail = 'Server detail'): HttpErrorResponse {
  return new HttpErrorResponse({
    status,
    error: code ? { type: 'about:blank', title: 'x', status, detail, code } : null,
  });
}

describe('sharing.logic', () => {
  it('isOwnerOnly: only 403 OWNER_ONLY', () => {
    expect(isOwnerOnly(httpError(403, 'OWNER_ONLY'))).toBe(true);
    expect(isOwnerOnly(httpError(403, 'CSRF_INVALID'))).toBe(false);
    expect(isOwnerOnly(httpError(404, 'OWNER_ONLY'))).toBe(false);
    expect(isOwnerOnly(new Error('x'))).toBe(false);
  });

  it('isInvitationNotFound: only 404 INVITATION_NOT_FOUND', () => {
    expect(isInvitationNotFound(httpError(404, 'INVITATION_NOT_FOUND'))).toBe(true);
    expect(isInvitationNotFound(httpError(404, 'LIST_NOT_FOUND'))).toBe(false);
  });

  it('hasSharingProblem matches status and code', () => {
    expect(hasSharingProblem(httpError(404, 'MEMBER_NOT_FOUND'), 404, 'MEMBER_NOT_FOUND')).toBe(
      true,
    );
    expect(hasSharingProblem(httpError(409, 'MEMBER_NOT_FOUND'), 404, 'MEMBER_NOT_FOUND')).toBe(
      false,
    );
  });

  it.each([
    'PERSONAL_LIST',
    'ALREADY_MEMBER',
    'ALREADY_INVITED',
    'MEMBER_LIMIT',
    'INVITATION_LIMIT',
  ])('inviteConflictMessage shows the detail of 409 %s', (code) => {
    expect(inviteConflictMessage(httpError(409, code))).toBe('Server detail');
  });

  it('inviteConflictMessage falls back to the contract text, ignores other errors', () => {
    expect(inviteConflictMessage(httpError(409, 'ALREADY_INVITED', ''))).toBe(
      'This email address already has a pending invitation',
    );
    expect(inviteConflictMessage(httpError(409, 'OWNER_CANNOT_LEAVE'))).toBeNull();
    expect(inviteConflictMessage(httpError(400, 'ALREADY_MEMBER'))).toBeNull();
    expect(inviteConflictMessage(httpError(409))).toBeNull();
  });

  it('memberLimitMessage and ownerCannotLeaveMessage', () => {
    expect(memberLimitMessage(httpError(409, 'MEMBER_LIMIT', ''))).toBe(
      'A list can have at most 20 members',
    );
    expect(memberLimitMessage(httpError(409, 'INVITATION_LIMIT'))).toBeNull();
    expect(ownerCannotLeaveMessage(httpError(409, 'OWNER_CANNOT_LEAVE', ''))).toBe(
      "The owner can't leave the list",
    );
    expect(ownerCannotLeaveMessage(httpError(409, 'MEMBER_LIMIT'))).toBeNull();
  });

  it('labels', () => {
    expect(memberCountLabel(1)).toBe('1 member');
    expect(memberCountLabel(3)).toBe('3 members');
    expect(invitationsLabel(0)).toBe('Invitations');
    expect(invitationsLabel(2)).toBe('Invitations, 2 pending');
  });

  it('formatDay shows a short date, or the raw text if unreadable', () => {
    expect(formatDay('2026-11-02T12:00:00Z')).toMatch(/^Nov [123], 2026$/);
    expect(formatDay('nope')).toBe('nope');
  });
});
