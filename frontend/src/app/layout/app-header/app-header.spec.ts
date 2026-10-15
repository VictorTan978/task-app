import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { AppHeader } from './app-header';

describe('AppHeader', () => {
  let fixture: ComponentFixture<AppHeader>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;
  let auth: AuthService;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [AppHeader],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush({
      id: 1,
      email: 'bob@example.com',
      displayName: 'Bob',
      emailVerified: true,
      hasPassword: true,
      role: 'USER',
      connections: [],
    });
    fixture = TestBed.createComponent(AppHeader);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const button = () => el.querySelector('button')!;
  const greeting = () => el.querySelector<HTMLAnchorElement>('a.app-header__greeting');

  it('shows app name and greeting', () => {
    expect(el.textContent).toContain('Task App');
    expect(el.textContent).toContain('Hi, Bob');
    expect(button().textContent).toContain('Log out');
  });

  it('links the greeting to /account', () => {
    expect(greeting()?.textContent?.trim()).toBe('Hi, Bob');
    expect(greeting()?.getAttribute('href')).toBe('/account');
  });

  it('shows the new name as soon as the signed-in user is updated', async () => {
    auth.updateCurrentUser({
      id: 1,
      email: 'bob@example.com',
      displayName: 'Bobby',
      emailVerified: true,
      hasPassword: true,
      role: 'USER',
      connections: [],
    });
    await fixture.whenStable();
    expect(greeting()?.textContent?.trim()).toBe('Hi, Bobby');
  });

  it('logs out: disabled while pending, then goes to /login', async () => {
    button().click();
    await fixture.whenStable();
    expect(button().disabled).toBe(true);
    http.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith('/login');
    expect(auth.isSignedIn()).toBe(false);
  });

  it('goes to /login even if logout fails', async () => {
    button().click();
    http.expectOne('/api/auth/logout').error(new ProgressEvent('error'));
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith('/login');
    expect(auth.isSignedIn()).toBe(false);
  });
  const adminLink = () => el.querySelector<HTMLAnchorElement>('[data-testid=admin-link]');

  it('shows no Admin link to normal users', () => {
    expect(adminLink()).toBeNull();
    expect(el.textContent).not.toContain('Admin');
  });

  it.each(['ADMIN', 'SUPER_ADMIN'] as const)(
    'shows an "Admin" link to /admin/users before the greeting for %s',
    async (role) => {
      auth.updateCurrentUser({ ...auth.currentUser()!, role });
      await fixture.whenStable();
      expect(adminLink()?.textContent?.trim()).toBe('Admin');
      expect(adminLink()?.getAttribute('href')).toBe('/admin/users');
      expect(
        Boolean(
          adminLink()!.compareDocumentPosition(greeting()!) & Node.DOCUMENT_POSITION_FOLLOWING,
        ),
      ).toBe(true);
    },
  );

  it('hides the Admin link as soon as the role is lowered', async () => {
    auth.updateCurrentUser({ ...auth.currentUser()!, role: 'ADMIN' });
    await fixture.whenStable();
    expect(adminLink()).not.toBeNull();
    auth.updateCurrentUser({ ...auth.currentUser()!, role: 'USER' });
    await fixture.whenStable();
    expect(adminLink()).toBeNull();
  });
});
