import { HttpXsrfTokenExtractor, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { GoogleStart } from './google-start';

describe('GoogleStart', () => {
  let http: HttpTestingController;
  let service: GoogleStart;
  let tokens: (string | null)[];
  let submit: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    tokens = [];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: HttpXsrfTokenExtractor,
          useValue: { getToken: () => (tokens.length > 1 ? tokens.shift()! : tokens[0]) ?? null },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(GoogleStart);
    submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => undefined);
  });

  afterEach(() => {
    http.verify();
    submit.mockRestore();
    document.getElementById('google-start-form')?.remove();
  });

  function submittedForm(): HTMLFormElement {
    expect(submit).toHaveBeenCalledTimes(1);
    return submit.mock.contexts[0] as HTMLFormElement;
  }

  function fields(form: HTMLFormElement): Record<string, string> {
    return Object.fromEntries(
      [...form.querySelectorAll('input')].map((input) => [input.name, input.value]),
    );
  }

  it.each(['login', 'connect'] as const)(
    'submits a hidden top-level POST form with intent=%s and _csrf',
    async (intent) => {
      tokens = ['tok-123'];
      expect(await firstValueFrom(service.start(intent))).toBe(true);

      const form = submittedForm();
      expect(form.method).toBe('post');
      expect(form.getAttribute('action')).toBe('/api/auth/oauth2/authorization/google');
      expect(form.target).toBe('');
      expect(form.hidden).toBe(true);
      expect(form.isConnected).toBe(true);
      expect([...form.querySelectorAll('input')].every((i) => i.type === 'hidden')).toBe(true);
      expect(fields(form)).toEqual({ intent, _csrf: 'tok-123' });
      http.expectNone('/api/auth/me');
    },
  );

  it('reads the token at submit time (a rotated cookie is used)', async () => {
    tokens = ['first'];
    await firstValueFrom(service.start('login'));
    tokens = ['rotated'];
    await firstValueFrom(service.start('login'));
    expect(submit).toHaveBeenCalledTimes(2);
    const latest = submit.mock.contexts[1] as HTMLFormElement;
    expect(fields(latest)['_csrf']).toBe('rotated');
    // Only one start form is ever in the page.
    expect(document.querySelectorAll('#google-start-form').length).toBe(1);
  });

  it('without a token calls GET /api/auth/me once, then submits with the new token', async () => {
    tokens = [null, 'fresh'];
    const result = firstValueFrom(service.start('login'));
    expect(submit).not.toHaveBeenCalled();
    http
      .expectOne('/api/auth/me')
      .flush(
        { type: 'about:blank', title: 'x', status: 401, detail: 'x', code: 'UNAUTHENTICATED' },
        { status: 401, statusText: 'Unauthorized' },
      );
    expect(await result).toBe(true);
    expect(fields(submittedForm())['_csrf']).toBe('fresh');
  });

  it('emits false and submits nothing when there is still no token after GET /me', async () => {
    tokens = [null];
    const result = firstValueFrom(service.start('login'));
    http.expectOne('/api/auth/me').error(new ProgressEvent('error'));
    expect(await result).toBe(false);
    expect(submit).not.toHaveBeenCalled();
    expect(document.getElementById('google-start-form')).toBeNull();
  });
});
