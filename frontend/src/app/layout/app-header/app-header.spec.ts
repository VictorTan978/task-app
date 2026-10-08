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
    http.expectOne('/api/auth/me').flush({ id: 1, email: 'bob@example.com', displayName: 'Bob' });
    fixture = TestBed.createComponent(AppHeader);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const button = () => el.querySelector('button')!;

  it('shows app name and greeting', () => {
    expect(el.textContent).toContain('Task App');
    expect(el.textContent).toContain('Hi, Bob');
    expect(button().textContent).toContain('Log out');
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
});
