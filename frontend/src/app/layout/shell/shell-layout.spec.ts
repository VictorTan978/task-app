import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ShellLayout } from './shell-layout';

describe('ShellLayout', () => {
  it('shows the verify-email banner right under the header for an unconfirmed user', async () => {
    TestBed.configureTestingModule({
      imports: [ShellLayout],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).loadMe().subscribe();
    http.expectOne('/api/auth/me').flush({
      id: 1,
      email: 'bob@example.com',
      displayName: 'Bob',
      emailVerified: false,
      hasPassword: true,
      connections: [],
    });

    const fixture = TestBed.createComponent(ShellLayout);
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    const header = el.querySelector('app-header')!;
    expect(header.nextElementSibling?.tagName.toLowerCase()).toBe('app-verify-email-banner');
    expect(el.querySelector('[data-testid=verify-email-banner]')).not.toBeNull();
    http.verify();
  });
});
