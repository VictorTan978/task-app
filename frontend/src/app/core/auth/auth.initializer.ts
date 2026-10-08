import { EnvironmentProviders, inject, provideAppInitializer } from '@angular/core';
import { AuthService } from './auth.service';

/** Resolves the current session (GET /api/auth/me) before the first navigation. */
export function provideAuthInitializer(): EnvironmentProviders {
  return provideAppInitializer(() => inject(AuthService).loadMe());
}
