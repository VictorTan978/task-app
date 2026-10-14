/** Relative API URLs (served through the dev proxy; no CORS). */
export const AUTH_API = {
  me: '/api/auth/me',
  register: '/api/auth/register',
  login: '/api/auth/login',
  logout: '/api/auth/logout',
  verifyEmail: '/api/auth/verify-email',
  forgotPassword: '/api/auth/forgot-password',
  checkResetToken: '/api/auth/check-reset-token',
  resetPassword: '/api/auth/reset-password',
  providers: '/api/auth/providers',
  /** Started by a top-level HTML form POST (never HttpClient). */
  googleStart: '/api/auth/oauth2/authorization/google',
} as const;
