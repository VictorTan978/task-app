import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { TaskList } from '../lists/lists.models';
import { SHARING_API } from './sharing-api';
import {
  CreateInvitationRequest,
  InvitationCount,
  ListInvitation,
  Member,
  MyInvitation,
} from './sharing.models';

/** Typed HTTP access to list members and invitations (0.13.0). Stateless. */
@Injectable({ providedIn: 'root' })
export class SharingService {
  private readonly http = inject(HttpClient);

  /** The list's members, owner first. Any member may call it. */
  members(listId: number): Observable<Member[]> {
    return this.http.get<Member[]>(SHARING_API.members(listId));
  }

  /** Owner only: remove a member (not the owner). */
  removeMember(listId: number, userId: number): Observable<void> {
    return this.http.delete<unknown>(SHARING_API.member(listId, userId)).pipe(map(() => undefined));
  }

  /** A member leaves the list (the owner can't). */
  leave(listId: number): Observable<void> {
    return this.http.post<unknown>(SHARING_API.leave(listId), null).pipe(map(() => undefined));
  }

  /** Owner only: the list's pending invitations, newest first. */
  listInvitations(listId: number): Observable<ListInvitation[]> {
    return this.http.get<ListInvitation[]>(SHARING_API.listInvitations(listId));
  }

  /** Owner only: invite an email address. Same answer whether or not an account exists. */
  invite(listId: number, request: CreateInvitationRequest): Observable<ListInvitation> {
    return this.http.post<ListInvitation>(SHARING_API.listInvitations(listId), request);
  }

  /** Owner only: cancel a pending invitation. */
  cancelInvitation(listId: number, invitationId: number): Observable<void> {
    return this.http
      .delete<unknown>(SHARING_API.listInvitation(listId, invitationId))
      .pipe(map(() => undefined));
  }

  /** My pending invitations, newest first (`[]` while my email is unverified). */
  myInvitations(): Observable<MyInvitation[]> {
    return this.http.get<MyInvitation[]>(SHARING_API.myInvitations);
  }

  /** How many pending invitations I have (header badge). */
  myInvitationCount(): Observable<number> {
    return this.http
      .get<InvitationCount>(SHARING_API.myInvitationCount)
      .pipe(map((body) => body.count));
  }

  /** Accept: I become a member; returns the list as `GET /api/lists` shows it. */
  accept(id: number): Observable<TaskList> {
    return this.http.post<TaskList>(SHARING_API.accept(id), null);
  }

  decline(id: number): Observable<void> {
    return this.http.post<unknown>(SHARING_API.decline(id), null).pipe(map(() => undefined));
  }
}
