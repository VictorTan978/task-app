import { HttpXsrfTokenExtractor } from '@angular/common/http';
import { DOCUMENT, inject, Injectable } from '@angular/core';
import { defer, map, Observable, of } from 'rxjs';
import { AUTH_API } from '../../../core/auth/auth-api';
import { GoogleIntent } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';

const FORM_ID = 'google-start-form';

/**
 * Starts the Google flow with a hidden top-level HTML form POST (not HttpClient), as the contract
 * requires: `intent` plus `_csrf` (the raw XSRF-TOKEN cookie value, read at submit time).
 */
@Injectable({ providedIn: 'root' })
export class GoogleStart {
  private readonly xsrf = inject(HttpXsrfTokenExtractor);
  private readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);

  /**
   * Reads the CSRF token; when there is none, calls GET /api/auth/me once (which sets the cookie)
   * and reads again. Emits true once the form was submitted (the page is navigating away), or
   * false when there is still no token (nothing submitted).
   */
  start(intent: GoogleIntent): Observable<boolean> {
    return defer(() => {
      const token = this.xsrf.getToken();
      return token ? of(token) : this.auth.refreshMe().pipe(map(() => this.xsrf.getToken()));
    }).pipe(
      map((token) => {
        if (!token) {
          return false;
        }
        this.submit(intent, token);
        return true;
      }),
    );
  }

  private submit(intent: GoogleIntent, token: string): void {
    this.document.getElementById(FORM_ID)?.remove();
    const form = this.document.createElement('form');
    form.id = FORM_ID;
    form.method = 'post';
    form.action = AUTH_API.googleStart;
    form.hidden = true;
    form.append(this.hiddenInput('intent', intent), this.hiddenInput('_csrf', token));
    this.document.body.append(form);
    form.submit();
  }

  private hiddenInput(name: string, value: string): HTMLInputElement {
    const input = this.document.createElement('input');
    input.type = 'hidden';
    input.name = name;
    input.value = value;
    return input;
  }
}
