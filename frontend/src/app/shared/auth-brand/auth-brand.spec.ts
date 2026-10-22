import { TestBed } from '@angular/core/testing';
import { AuthBrand } from './auth-brand';

describe('AuthBrand', () => {
  it('renders a decorative logo and the app name', () => {
    const fixture = TestBed.createComponent(AuthBrand);
    const el: HTMLElement = fixture.nativeElement;
    const img = el.querySelector('img')!;
    expect(img.getAttribute('src')).toBe('logo.svg');
    expect(img.getAttribute('alt')).toBe('');
    expect(el.textContent).toContain('Task App');
  });
});
