import { HttpErrorResponse } from '@angular/common/http';
import { readProblem } from '../../core/auth/problem';
import { SharingProblem, SharingProblemCode } from './sharing.models';

export const OWNER_ONLY_MESSAGE = 'Only the owner can do that';
export const INVITATION_INVALID_MESSAGE = 'This invitation is no longer valid';
export const INVITATION_SENT_MESSAGE = 'Invitation sent';
export const NO_PENDING_INVITATIONS = 'No pending invitations';
export const CONFIRM_EMAIL_FOR_INVITATIONS = 'Confirm your email to see invitations';
export const INVITATIONS_LOAD_ERROR = "Couldn't load your invitations.";
export const MEMBERS_LOAD_ERROR = "Couldn't load the members.";
export const PENDING_LOAD_ERROR = "Couldn't load the pending invitations.";
export const INVITE_ERROR = "Couldn't send the invitation. Please try again.";
export const CANCEL_INVITE_ERROR = "Couldn't cancel the invitation. Please try again.";
export const REMOVE_MEMBER_ERROR = "Couldn't remove the member. Please try again.";
export const LEAVE_ERROR = "Couldn't leave the list. Please try again.";
export const ACCEPT_ERROR = "Couldn't accept the invitation. Please try again.";
export const DECLINE_ERROR = "Couldn't decline the invitation. Please try again.";

/** The sharing problem of an HTTP error, or null. */
export function sharingProblem(error: unknown): SharingProblem | null {
  return readProblem(error) as SharingProblem | null;
}

/** True if the error is an HTTP error with this status and sharing problem code. */
export function hasSharingProblem(
  error: unknown,
  status: number,
  code: SharingProblemCode,
): boolean {
  const problem = sharingProblem(error);
  return error instanceof HttpErrorResponse && error.status === status && problem?.code === code;
}

/** 403 `OWNER_ONLY`: I'm a member, not the owner (my role changed or the page is stale). */
export function isOwnerOnly(error: unknown): boolean {
  return hasSharingProblem(error, 403, 'OWNER_ONLY');
}

/** 404 `INVITATION_NOT_FOUND`: cancelled, expired, declined, accepted or its list deleted. */
export function isInvitationNotFound(error: unknown): boolean {
  return hasSharingProblem(error, 404, 'INVITATION_NOT_FOUND');
}

/** The invite 409s whose `detail` is shown next to the email field. */
const INVITE_CONFLICTS: readonly SharingProblemCode[] = [
  'PERSONAL_LIST',
  'ALREADY_MEMBER',
  'ALREADY_INVITED',
  'MEMBER_LIMIT',
  'INVITATION_LIMIT',
];

/** Fallback texts (the contract's `detail`s) when a 409 comes without a body. */
const CONFLICT_MESSAGES: Partial<Record<SharingProblemCode, string>> = {
  PERSONAL_LIST: "Your personal list can't be shared",
  ALREADY_MEMBER: 'This person is already a member of the list',
  ALREADY_INVITED: 'This email address already has a pending invitation',
  MEMBER_LIMIT: 'A list can have at most 20 members',
  INVITATION_LIMIT: 'A list can have at most 20 pending invitations',
  OWNER_CANNOT_LEAVE: "The owner can't leave the list",
};

/** Message for an invite 409 (already member/invited, limits, "My tasks"), else null. */
export function inviteConflictMessage(error: unknown): string | null {
  const problem = sharingProblem(error);
  if (!(error instanceof HttpErrorResponse) || error.status !== 409 || !problem) {
    return null;
  }
  const code = problem.code as SharingProblemCode;
  if (!INVITE_CONFLICTS.includes(code)) {
    return null;
  }
  return problem.detail || (CONFLICT_MESSAGES[code] ?? null);
}

/** The 409 `MEMBER_LIMIT` message (accepting into a full list). */
export function memberLimitMessage(error: unknown): string | null {
  return hasSharingProblem(error, 409, 'MEMBER_LIMIT')
    ? sharingProblem(error)?.detail || (CONFLICT_MESSAGES.MEMBER_LIMIT ?? null)
    : null;
}

/** The 409 `OWNER_CANNOT_LEAVE` message, else null. */
export function ownerCannotLeaveMessage(error: unknown): string | null {
  return hasSharingProblem(error, 409, 'OWNER_CANNOT_LEAVE')
    ? sharingProblem(error)?.detail || (CONFLICT_MESSAGES.OWNER_CANNOT_LEAVE ?? null)
    : null;
}

/** "3 members" / "1 member". */
export function memberCountLabel(count: number): string {
  return `${count} ${count === 1 ? 'member' : 'members'}`;
}

/** "Invitations" / "Invitations, 2 pending" (header link's accessible name). */
export function invitationsLabel(count: number): string {
  return count > 0 ? `Invitations, ${count} pending` : 'Invitations';
}

const DAY_FORMAT = new Intl.DateTimeFormat('en-US', {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
});

/** "Nov 2, 2026" for an API instant (local time zone), or the raw text if it can't be read. */
export function formatDay(instant: string): string {
  const date = new Date(instant);
  return Number.isNaN(date.getTime()) ? instant : DAY_FORMAT.format(date);
}
