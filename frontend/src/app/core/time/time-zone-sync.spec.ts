import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../auth/auth.service';
import { browserTimeZone } from './now.service';
import { TimeZoneSync } from './time-zone-sync';

const BOB = {
  id: 1,
  email: 'bob@example.com',
  displayName: 'Bob',
  emailVerified: true,
  hasPassword: true,
  role: 'USER' as const,
  timeZone: null as string | null,
  reminderEmails: true,
  connections: [],
};

describe('TimeZoneSync', () => {
  let http: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    TestBed.inject(TimeZoneSync);
  });

  afterEach(() => http.verify());

  async function signIn(user: typeof BOB) {
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(user);
    TestBed.tick();
  }

  it('sends the browser zone with ifUnset when the account has none', async () => {
    await signIn(BOB);
    const req = http.expectOne('/api/account/time-zone');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ timeZone: browserTimeZone(), ifUnset: true });
    req.flush({ ...BOB, timeZone: browserTimeZone() });
    expect(auth.currentUser()?.timeZone).toBe(browserTimeZone());
  });

  it('does nothing when a zone is stored', async () => {
    await signIn({ ...BOB, timeZone: 'Asia/Tokyo' });
    http.expectNone('/api/account/time-zone');
  });

  it('is silent on failure and does not try again for the same user', async () => {
    await signIn(BOB);
    http.expectOne('/api/account/time-zone').flush(null, { status: 500, statusText: 'x' });
    auth.updateCurrentUser({ ...BOB, displayName: 'Bobby' });
    TestBed.tick();
    http.expectNone('/api/account/time-zone');
    expect(auth.currentUser()?.timeZone).toBeNull();
  });
});
