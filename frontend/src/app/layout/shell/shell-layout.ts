import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AppHeader } from '../app-header/app-header';
import { VerifyEmailBanner } from '../verify-email-banner/verify-email-banner';

@Component({
  selector: 'app-shell-layout',
  imports: [AppHeader, VerifyEmailBanner, RouterOutlet],
  template: `
    <app-header />
    <app-verify-email-banner />
    <main class="shell-main">
      <router-outlet />
    </main>
  `,
  styles: `
    .shell-main {
      max-width: 960px;
      margin: 0 auto;
      padding: 2rem 1.5rem;
    }
  `,
})
export class ShellLayout {}
