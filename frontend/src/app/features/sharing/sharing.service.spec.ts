import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { TaskList } from '../lists/lists.models';
import { ListInvitation, Member, MyInvitation } from './sharing.models';
import { SharingService } from './sharing.service';

const MEMBER: Member = {
  userId: 9,
  displayName: 'Bob',
  email: 'bob@example.com',
  role: 'MEMBER',
  joinedAt: '2026-10-19T10:12:30.5Z',
};
const INVITATION: ListInvitation = {
  id: 31,
  email: 'carol@example.com',
  invitedAt: '2026-10-19T10:00:00Z',
  expiresAt: '2026-11-02T10:00:00Z',
};
const MINE: MyInvitation = {
  id: 31,
  list: { id: 8, name: 'Home' },
  invitedBy: { displayName: 'Ana' },
  invitedAt: '2026-10-19T10:00:00Z',
  expiresAt: '2026-11-02T10:00:00Z',
};
const HOME: TaskList = { id: 8, name: 'Home', personal: false, role: 'MEMBER', memberCount: 4 };

describe('SharingService', () => {
  let service: SharingService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SharingService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('members sends GET /api/lists/{id}/members', async () => {
    const result = firstValueFrom(service.members(8));
    const req = http.expectOne('/api/lists/8/members');
    expect(req.request.method).toBe('GET');
    req.flush([MEMBER]);
    expect(await result).toEqual([MEMBER]);
  });

  it('removeMember sends DELETE /api/lists/{id}/members/{userId}', async () => {
    const result = firstValueFrom(service.removeMember(8, 9));
    const req = http.expectOne('/api/lists/8/members/9');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
  });

  it('leave sends POST /api/lists/{id}/leave with no body', async () => {
    const result = firstValueFrom(service.leave(8));
    const req = http.expectOne('/api/lists/8/leave');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
  });

  it('listInvitations sends GET /api/lists/{id}/invitations', async () => {
    const result = firstValueFrom(service.listInvitations(8));
    const req = http.expectOne('/api/lists/8/invitations');
    expect(req.request.method).toBe('GET');
    req.flush([INVITATION]);
    expect(await result).toEqual([INVITATION]);
  });

  it('invite sends POST /api/lists/{id}/invitations with {email}', async () => {
    const result = firstValueFrom(service.invite(8, { email: 'carol@example.com' }));
    const req = http.expectOne('/api/lists/8/invitations');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'carol@example.com' });
    req.flush(INVITATION, { status: 201, statusText: 'Created' });
    expect(await result).toEqual(INVITATION);
  });

  it('cancelInvitation sends DELETE /api/lists/{id}/invitations/{invitationId}', async () => {
    const result = firstValueFrom(service.cancelInvitation(8, 31));
    const req = http.expectOne('/api/lists/8/invitations/31');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
  });

  it('myInvitations sends GET /api/invitations', async () => {
    const result = firstValueFrom(service.myInvitations());
    const req = http.expectOne('/api/invitations');
    expect(req.request.method).toBe('GET');
    req.flush([MINE]);
    expect(await result).toEqual([MINE]);
  });

  it('myInvitationCount sends GET /api/invitations/count and returns the number', async () => {
    const result = firstValueFrom(service.myInvitationCount());
    const req = http.expectOne('/api/invitations/count');
    expect(req.request.method).toBe('GET');
    req.flush({ count: 2 });
    expect(await result).toBe(2);
  });

  it('accept sends POST /api/invitations/{id}/accept and returns the list', async () => {
    const result = firstValueFrom(service.accept(31));
    const req = http.expectOne('/api/invitations/31/accept');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush(HOME);
    expect(await result).toEqual(HOME);
  });

  it('decline sends POST /api/invitations/{id}/decline', async () => {
    const result = firstValueFrom(service.decline(31));
    const req = http.expectOne('/api/invitations/31/decline');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
  });
});
