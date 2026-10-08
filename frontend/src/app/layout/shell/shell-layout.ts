import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AppHeader } from '../app-header/app-header';

@Component({
  selector: 'app-shell-layout',
  imports: [AppHeader, RouterOutlet],
  template: `
    <app-header />
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
