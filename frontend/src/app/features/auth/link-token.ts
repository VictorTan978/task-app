import { inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

/**
 * Reads the `token` query parameter of an emailed link (opaque, never validated here) and removes
 * it from the address bar and the current history entry, so it does not linger in history,
 * bookmarks or screenshots. Must run in an injection context (e.g. a component constructor).
 * Returns null when the parameter is missing or empty.
 */
export function takeLinkToken(): string | null {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  const params = route.snapshot.queryParamMap;
  const token = params.get('token');
  if (params.has('token')) {
    void router.navigate([], {
      relativeTo: route,
      queryParams: { token: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
  return token ? token : null;
}
