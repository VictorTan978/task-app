import { FormControl } from '@angular/forms';
import { emailTrimmed, fieldMessage, maxTrimmed, passwordLength, requiredTrimmed } from './auth-form';

describe('auth form validators', () => {
  const c = (value: string) => new FormControl(value);

  it('requiredTrimmed rejects blank and spaces-only', () => {
    expect(requiredTrimmed(c(''))).toEqual({ REQUIRED: true });
    expect(requiredTrimmed(c('   '))).toEqual({ REQUIRED: true });
    expect(requiredTrimmed(c(' a '))).toBeNull();
  });

  it('emailTrimmed validates the trimmed value', () => {
    expect(emailTrimmed(c('  Bob@Example.com '))).toBeNull();
    expect(emailTrimmed(c('not-an-email'))).toEqual({ INVALID_EMAIL: true });
    expect(emailTrimmed(c(''))).toBeNull();
  });

  it('maxTrimmed counts trimmed length', () => {
    expect(maxTrimmed(3)(c('  abc  '))).toBeNull();
    expect(maxTrimmed(3)(c('abcd'))).toEqual({ TOO_LONG: true });
  });

  it('passwordLength enforces 8-72 characters and 72 UTF-8 bytes', () => {
    expect(passwordLength(c('1234567'))).toEqual({ PASSWORD_LENGTH: true });
    expect(passwordLength(c('12345678'))).toBeNull();
    expect(passwordLength(c('a'.repeat(72)))).toBeNull();
    expect(passwordLength(c('a'.repeat(73)))).toEqual({ PASSWORD_LENGTH: true });
    expect(passwordLength(c('é'.repeat(36)))).toBeNull();
    expect(passwordLength(c('é'.repeat(37)))).toEqual({ PASSWORD_LENGTH: true });
    expect(passwordLength(c('   '))).toBeNull();
  });

  it('fieldMessage prefers REQUIRED and maps server codes', () => {
    const control = c('');
    control.setErrors({ REQUIRED: true, TOO_LONG: true });
    expect(fieldMessage('email', control)).toBe('Enter your email');
    control.setErrors({ server: { code: 'EMAIL_TAKEN', message: 'srv' } });
    expect(fieldMessage('email', control)).toBe('An account with this email already exists');
  });
});
