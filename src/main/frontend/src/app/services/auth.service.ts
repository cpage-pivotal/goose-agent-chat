import { Injectable, signal } from '@angular/core';

export type AuthMode = 'oauth2' | 'password';

export interface AuthStatus {
  authenticated: boolean;
  userId: string;
  username: string;
  email: string;
  displayName: string;
  mode: AuthMode;
  loginUrl: string;
}

const FALLBACK_STATUS: AuthStatus = {
  authenticated: false,
  userId: '',
  username: '',
  email: '',
  displayName: '',
  mode: 'oauth2',
  loginUrl: '/oauth2/authorization/sso'
};

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private _authStatus = signal<AuthStatus | null>(null);
  readonly authStatus = this._authStatus.asReadonly();

  async checkAuthStatus(): Promise<AuthStatus> {
    try {
      const response = await fetch('/auth/status', { credentials: 'same-origin' });
      if (!response.ok) {
        return FALLBACK_STATUS;
      }
      const status: AuthStatus = await response.json();
      this._authStatus.set(status);
      return status;
    } catch {
      return FALLBACK_STATUS;
    }
  }

  logout(): void {
    window.location.href = '/logout';
  }
}
