import { inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { OAuthResult } from '../../core/auth/auth.models';

/**
 * Reads the `oauth` query parameter the server adds after a Google round trip and removes it
 * from the address bar (replacing the history entry), so a refresh doesn't repeat the notice.
 * Returns the code only when it is one of `accepted`; unknown codes are ignored.
 * Must run in an injection context (e.g. a component constructor).
 */
export function takeOAuthResult<T extends OAuthResult>(accepted: readonly T[]): T | null {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  const params = route.snapshot.queryParamMap;
  if (!params.has('oauth')) {
    return null;
  }
  const value = params.get('oauth');
  void router.navigate([], {
    relativeTo: route,
    queryParams: { oauth: null },
    queryParamsHandling: 'merge',
    replaceUrl: true,
  });
  return (accepted as readonly string[]).includes(value ?? '') ? (value as T) : null;
}
