import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { User } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { VerifyEmailBanner } from './verify-email-banner';

const BOB: User = { id: 1, email: 'bob@example.com', displayName: 'Bob', emailVerified: false };

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code };
}

describe('VerifyEmailBanner', () => {
  let fixture: ComponentFixture<VerifyEmailBanner>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [VerifyEmailBanner],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => http.verify());

  async function render(user: User | null) {
    if (user) {
      auth.loadMe().subscribe();
      http.expectOne('/api/auth/me').flush(user);
    }
    fixture = TestBed.createComponent(VerifyEmailBanner);
    el = fixture.nativeElement;
    await fixture.whenStable();
  }

  const banner = () => el.querySelector('[data-testid=verify-email-banner]');

  it('shows the text with the email and a "Resend email" button when unconfirmed', async () => {
    await render(BOB);
    expect(banner()?.textContent).toContain(
      'Please confirm your email address. We sent a link to bob@example.com.',
    );
    expect(el.querySelector('#resend-verification')?.textContent?.trim()).toBe('Resend email');
  });

  it('is hidden when the email is confirmed', async () => {
    await render({ ...BOB, emailVerified: true });
    expect(banner()).toBeNull();
  });

  it('is hidden when signed out', async () => {
    await render(null);
    expect(banner()).toBeNull();
  });

  it('resend 204 shows "Email sent" in the banner', async () => {
    await render(BOB);
    el.querySelector<HTMLButtonElement>('#resend-verification')!.click();
    http
      .expectOne('/api/account/resend-verification')
      .flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(banner()?.textContent).toContain('Email sent');
  });

  it('hides after 409 ALREADY_VERIFIED once the user is refetched', async () => {
    await render(BOB);
    el.querySelector<HTMLButtonElement>('#resend-verification')!.click();
    http
      .expectOne('/api/account/resend-verification')
      .flush(problem(409, 'ALREADY_VERIFIED'), { status: 409, statusText: 'Conflict' });
    http.expectOne('/api/auth/me').flush({ ...BOB, emailVerified: true });
    await fixture.whenStable();
    expect(banner()).toBeNull();
  });

  it('hides as soon as the signed-in user becomes confirmed', async () => {
    await render(BOB);
    auth.updateCurrentUser({ ...BOB, emailVerified: true });
    await fixture.whenStable();
    expect(banner()).toBeNull();
  });
});
