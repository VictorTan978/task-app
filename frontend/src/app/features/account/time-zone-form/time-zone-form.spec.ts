import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { TimeZoneForm, timeZoneOptions } from './time-zone-form';

const BOB = {
  id: 1,
  email: 'bob@example.com',
  displayName: 'Bob',
  emailVerified: true,
  hasPassword: true,
  role: 'USER' as const,
  timeZone: 'Pacific/Fiji' as string | null,
  reminderEmails: true,
  connections: [],
};
const URL = '/api/account/time-zone';

describe('TimeZoneForm', () => {
  let fixture: ComponentFixture<TimeZoneForm>;
  let el: HTMLElement;
  let http: HttpTestingController;

  async function create(user = BOB) {
    TestBed.configureTestingModule({
      imports: [TimeZoneForm],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(user);
    fixture = TestBed.createComponent(TimeZoneForm);
    el = fixture.nativeElement;
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  const input = () => el.querySelector<HTMLInputElement>('#account-time-zone')!;
  const hint = () => el.querySelector('[data-testid=time-zone-hint]');
  const submit = async () => {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  };

  it('starts with the stored zone, lists zones, and saves without ifUnset', async () => {
    await create();
    expect(input().value).toBe('Pacific/Fiji');
    expect(el.querySelectorAll('#account-time-zone-options option').length).toBeGreaterThan(0);
    input().value = 'Asia/Tokyo';
    input().dispatchEvent(new Event('input'));
    await submit();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ timeZone: 'Asia/Tokyo' });
    req.flush({ ...BOB, timeZone: 'Asia/Tokyo' });
    await fixture.whenStable();
    expect(el.querySelector('[role=status]')?.textContent).toContain('Time zone updated');
  });

  it('shows the server field error for an unknown zone', async () => {
    await create();
    input().value = 'Mars/Base';
    input().dispatchEvent(new Event('input'));
    await submit();
    http.expectOne(URL).flush(
      {
        type: 'about:blank',
        title: 'x',
        status: 400,
        detail: 'x',
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'timeZone', code: 'INVALID_TIME_ZONE', message: 'Unknown zone' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();
    expect(el.querySelector('#account-time-zone-error')?.textContent).toContain(
      'Unknown time zone',
    );
  });

  it('refuses an empty zone without a request', async () => {
    await create();
    input().value = '  ';
    input().dispatchEvent(new Event('input'));
    await submit();
    expect(el.querySelector('#account-time-zone-error')?.textContent).toContain(
      'Enter a time zone',
    );
  });

  it('offers "Use <browser zone>" when it differs, and saves it', async () => {
    await create();
    const browser = Intl.DateTimeFormat().resolvedOptions().timeZone;
    expect(hint()?.textContent).toContain(`Use ${browser}`);
    el.querySelector<HTMLButtonElement>('[data-testid=use-browser-zone]')!.click();
    await fixture.whenStable();
    const req = http.expectOne(URL);
    expect(req.request.body).toEqual({ timeZone: browser });
    req.flush({ ...BOB, timeZone: browser });
    await fixture.whenStable();
    expect(hint()).toBeNull();
  });

  it('shows no hint when the stored zone is the browser zone', async () => {
    await create({ ...BOB, timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone });
    expect(hint()).toBeNull();
  });

  it('timeZoneOptions always includes the extra zones, sorted and unique', () => {
    const list = timeZoneOptions(['Zzz/Custom', null, 'Zzz/Custom']);
    expect(list.filter((z) => z === 'Zzz/Custom').length).toBe(1);
    expect([...list].sort()).toEqual(list);
  });
});
