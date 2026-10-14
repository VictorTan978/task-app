import { HttpXsrfTokenExtractor, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GoogleButton } from './google-button';

describe('GoogleButton', () => {
  let fixture: ComponentFixture<GoogleButton>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let token: string | null;
  let submit: ReturnType<typeof vi.spyOn>;

  async function render(providers: () => void) {
    token = 'tok-1';
    TestBed.configureTestingModule({
      imports: [GoogleButton],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => token } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GoogleButton);
    el = fixture.nativeElement;
    providers();
    await fixture.whenStable();
  }

  const flushProviders = (google: boolean) => () =>
    http.expectOne('/api/auth/providers').flush({ google });
  const button = () => el.querySelector<HTMLButtonElement>('[data-testid=google-button]');

  beforeEach(() => {
    submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => undefined);
  });

  afterEach(() => {
    http.verify();
    submit.mockRestore();
    document.getElementById('google-start-form')?.remove();
  });

  it('renders nothing when Google sign-in is not configured', async () => {
    await render(flushProviders(false));
    expect(button()).toBeNull();
    expect(el.textContent?.trim()).toBe('');
  });

  it('renders nothing when the providers call fails', async () => {
    await render(() =>
      http.expectOne('/api/auth/providers').flush(null, { status: 500, statusText: 'x' }),
    );
    expect(button()).toBeNull();
  });

  it('shows the "or" divider and a white "Continue with Google" button with the G logo', async () => {
    await render(flushProviders(true));
    expect(el.querySelector('.google-sign-in__divider')?.textContent?.trim()).toBe('or');
    expect(button()!.type).toBe('button');
    expect(button()!.textContent?.trim()).toBe('Continue with Google');
    expect(button()!.querySelector('svg[aria-hidden=true]')).not.toBeNull();
    expect(button()!.disabled).toBe(false);
  });

  it('submits the login form, then stays disabled with a spinner while the page redirects', async () => {
    await render(flushProviders(true));
    button()!.click();
    await fixture.whenStable();

    expect(submit).toHaveBeenCalledTimes(1);
    const form = submit.mock.contexts[0] as HTMLFormElement;
    expect(form.getAttribute('action')).toBe('/api/auth/oauth2/authorization/google');
    expect(form.method).toBe('post');
    expect((form.elements.namedItem('intent') as HTMLInputElement).value).toBe('login');
    expect((form.elements.namedItem('_csrf') as HTMLInputElement).value).toBe('tok-1');
    expect(button()!.disabled).toBe(true);
    expect(button()!.getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('[data-testid=google-spinner]')).not.toBeNull();

    // No second submit while redirecting.
    button()!.click();
    expect(submit).toHaveBeenCalledTimes(1);
  });

  it('resets the spinner when the page is restored from the back/forward cache', async () => {
    await render(flushProviders(true));
    button()!.click();
    await fixture.whenStable();
    expect(button()!.disabled).toBe(true);

    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: false }));
    await fixture.whenStable();
    expect(button()!.disabled).toBe(true);

    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));
    await fixture.whenStable();
    expect(button()!.disabled).toBe(false);
    expect(el.querySelector('[data-testid=google-spinner]')).toBeNull();
  });

  it('without a CSRF token: calls GET /me once and shows the error if there is still none', async () => {
    await render(flushProviders(true));
    token = null;
    button()!.click();
    await fixture.whenStable();
    expect(button()!.disabled).toBe(true);
    http.expectOne('/api/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    expect(submit).not.toHaveBeenCalled();
    expect(el.querySelector('[role=alert]')?.textContent?.trim()).toBe(
      'Something went wrong, please try again',
    );
    expect(button()!.disabled).toBe(false);

    // A retry clears the error.
    token = 'tok-2';
    button()!.click();
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')).toBeNull();
    expect(submit).toHaveBeenCalledTimes(1);
  });
});
