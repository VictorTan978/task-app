/**
 * Gate 6 (approved, narrow): the emailed invitation link opens `/invitations` after sign-in.
 *
 * When a signed-out user opens `/invitations`, `authGuard` remembers it here (sessionStorage,
 * this tab only). The next time a signed-in user enters the app (after a password login,
 * registration, or the Google round trip, which all land on `/tasks`), `authGuard` takes it
 * once and goes to `/invitations` instead. Only this one fixed path is ever stored or used:
 * nothing comes from the URL, so it can't become an open redirect.
 */
export const INVITATIONS_PATH = '/invitations';

const STORAGE_KEY = 'taskapp.afterSignIn';

function storage(): Storage | null {
  try {
    return globalThis.sessionStorage ?? null;
  } catch {
    // Storage blocked (privacy settings): the redirect is simply skipped.
    return null;
  }
}

/** Remembers that `/invitations` should open after the next sign-in. */
export function rememberInvitationsAfterSignIn(): void {
  try {
    storage()?.setItem(STORAGE_KEY, INVITATIONS_PATH);
  } catch {
    // Quota or blocked storage: ignore.
  }
}

/**
 * Returns `/invitations` once if it was remembered (and forgets it), else null. Any other stored
 * value is ignored and cleared.
 */
export function takeAfterSignInPath(): string | null {
  const store = storage();
  if (!store) {
    return null;
  }
  let value: string | null;
  try {
    value = store.getItem(STORAGE_KEY);
    if (value !== null) {
      store.removeItem(STORAGE_KEY);
    }
  } catch {
    return null;
  }
  return value === INVITATIONS_PATH ? INVITATIONS_PATH : null;
}
