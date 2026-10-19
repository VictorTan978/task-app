/** Relative API URLs of the sharing endpoints (served through the dev proxy; no CORS). */
export const SHARING_API = {
  members: (listId: number) => `/api/lists/${listId}/members`,
  member: (listId: number, userId: number) => `/api/lists/${listId}/members/${userId}`,
  leave: (listId: number) => `/api/lists/${listId}/leave`,
  listInvitations: (listId: number) => `/api/lists/${listId}/invitations`,
  listInvitation: (listId: number, invitationId: number) =>
    `/api/lists/${listId}/invitations/${invitationId}`,
  myInvitations: '/api/invitations',
  myInvitationCount: '/api/invitations/count',
  accept: (id: number) => `/api/invitations/${id}/accept`,
  decline: (id: number) => `/api/invitations/${id}/decline`,
} as const;
