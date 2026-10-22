import { Component } from '@angular/core';

/** App logo with the name under it, centered above the heading of an auth card. */
@Component({
  selector: 'app-auth-brand',
  template: `
    <img class="auth-brand__logo" src="logo.svg" alt="" width="56" height="56" />
    <span class="auth-brand__name">Task App</span>
  `,
  host: { class: 'auth-brand' },
})
export class AuthBrand {}
