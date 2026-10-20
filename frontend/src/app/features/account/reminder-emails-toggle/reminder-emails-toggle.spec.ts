import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ReminderEmailsToggle } from './reminder-emails-toggle';

const BOB = {
  id: 1,
  email: 'bob@example.com',
  displayName: 'Bob',
  emailVerified: true,
  hasPassword: true,
  role: 'USER' as const,
  timeZone: 'Europe/Bucharest',
  reminderEmails: true,
  connections: [],
};
const URL = '/api/account/reminder-emails';

describe('ReminderEmailsToggle', () => {
  let fixture: ComponentFixture<ReminderEmailsToggle>;
  let el: HTMLElement;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [ReminderEmailsToggle],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    fixture = TestBed.createComponent(ReminderEmailsToggle);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const box = () => el.querySelector<HTMLInputElement>('#account-reminder-emails')!;

  it('reflects the stored setting and saves immediately when switched', async () => {
    expect(box().checked).toBe(true);
    box().click();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: false });
    await fixture.whenStable();
    expect(box().disabled).toBe(true);
    req.flush({ ...BOB, reminderEmails: false });
    await fixture.whenStable();
    expect(box().checked).toBe(false);
    expect(box().disabled).toBe(false);
    expect(el.querySelector('[role=status]')?.textContent).toContain('Saved');
  });

  it('goes back to the stored value and shows an error when saving fails', async () => {
    box().click();
    http.expectOne(URL).flush(null, { status: 500, statusText: 'x' });
    await fixture.whenStable();
    expect(box().checked).toBe(true);
    expect(el.querySelector('[role=alert]')?.textContent).toContain("Couldn't save");
  });
});
