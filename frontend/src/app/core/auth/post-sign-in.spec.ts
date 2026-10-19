import {
  INVITATIONS_PATH,
  rememberInvitationsAfterSignIn,
  takeAfterSignInPath,
} from './post-sign-in';

const KEY = 'taskapp.afterSignIn';

describe('post-sign-in (invitation link)', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('returns nothing when nothing was remembered', () => {
    expect(takeAfterSignInPath()).toBeNull();
  });

  it('remembers /invitations and returns it exactly once', () => {
    rememberInvitationsAfterSignIn();
    expect(sessionStorage.getItem(KEY)).toBe('/invitations');
    expect(takeAfterSignInPath()).toBe(INVITATIONS_PATH);
    expect(sessionStorage.getItem(KEY)).toBeNull();
    expect(takeAfterSignInPath()).toBeNull();
  });

  it.each(['/admin/users', '//evil.example.com', 'https://evil.example.com', '/invitations?x=1'])(
    'never returns any other stored value (%s), and clears it',
    (value) => {
      sessionStorage.setItem(KEY, value);
      expect(takeAfterSignInPath()).toBeNull();
      expect(sessionStorage.getItem(KEY)).toBeNull();
    },
  );

  it('blocked storage is ignored', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(() => rememberInvitationsAfterSignIn()).not.toThrow();
    expect(takeAfterSignInPath()).toBeNull();
  });
});
