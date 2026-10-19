/** Models matching the sharing schemas in `contracts/openapi.yaml` (v0.13.0) exactly. */
import { Problem } from '../../core/auth/auth.models';
import { ListRole } from '../lists/lists.models';

/** `Member`: a member of a list (`GET /api/lists/{id}/members`), the owner first. */
export interface Member {
  /** Use it with `DELETE /api/lists/{id}/members/{userId}`. */
  userId: number;
  displayName: string;
  /** The member's account email; shown only to members of this list. */
  email: string;
  role: ListRole;
  /** UTC instant, same format as `Task.createdAt`. */
  joinedAt: string;
}

/** `ListInvitation`: a pending invitation as the owner sees it. Never says if an account exists. */
export interface ListInvitation {
  /** Use it with `DELETE /api/lists/{id}/invitations/{invitationId}`. */
  id: number;
  /** Trimmed and lowercased. */
  email: string;
  invitedAt: string;
  /** `invitedAt` + 14 days. */
  expiresAt: string;
}

/** `MyInvitation`: a pending invitation as the invitee sees it (`GET /api/invitations`). */
export interface MyInvitation {
  /** Use it with `POST /api/invitations/{id}/accept` and `.../decline`. */
  id: number;
  list: { id: number; name: string };
  /** The list owner. */
  invitedBy: { displayName: string };
  invitedAt: string;
  expiresAt: string;
}

/** `CreateInvitationRequest`: body of `POST /api/lists/{id}/invitations`. */
export interface CreateInvitationRequest {
  email: string;
}

/** `InvitationCount`: body of `GET /api/invitations/count`. */
export interface InvitationCount {
  count: number;
}

/** Sharing problem codes (0.13.0), plus the list codes the sharing endpoints also return. */
export type SharingProblemCode =
  | 'LIST_NOT_FOUND'
  | 'PERSONAL_LIST'
  | 'OWNER_ONLY'
  | 'OWNER_CANNOT_LEAVE'
  | 'ALREADY_MEMBER'
  | 'ALREADY_INVITED'
  | 'MEMBER_LIMIT'
  | 'INVITATION_LIMIT'
  | 'MEMBER_NOT_FOUND'
  | 'INVITATION_NOT_FOUND';

/** `Problem` from the sharing endpoints. */
export type SharingProblem = Omit<Problem, 'code'> & {
  code: Problem['code'] | SharingProblemCode;
};
