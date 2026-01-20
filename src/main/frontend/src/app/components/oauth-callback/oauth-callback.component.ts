import { Component, OnInit, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { McpOAuthService } from '../../services/mcp-oauth.service';

/**
 * Component for handling OAuth callback redirects.
 * 
 * This component is displayed when the OAuth provider redirects back
 * to the application with an authorization code. It processes the callback
 * and displays the result to the user.
 * 
 * In most cases, the backend handles the callback directly and returns HTML.
 * This component is a fallback for cases where client-side processing is needed.
 */
@Component({
  selector: 'app-oauth-callback',
  standalone: true,
  imports: [
    MatProgressSpinnerModule,
    MatIconModule,
    MatButtonModule
  ],
  template: `
    <div class="callback-container">
      @if (loading()) {
        <div class="loading-state">
          <mat-spinner diameter="48"></mat-spinner>
          <h2>Processing authentication...</h2>
          <p>Please wait while we complete the authorization.</p>
        </div>
      } @else if (error()) {
        <div class="error-state">
          <mat-icon class="status-icon error">error</mat-icon>
          <h2>Authentication Failed</h2>
          <p>{{ error() }}</p>
          <button mat-raised-button color="primary" (click)="closeWindow()">
            Close Window
          </button>
        </div>
      } @else {
        <div class="success-state">
          <mat-icon class="status-icon success">check_circle</mat-icon>
          <h2>Authentication Successful</h2>
          <p>Successfully connected to {{ serverName() }}.</p>
          <p class="hint">You can close this window.</p>
          <button mat-raised-button color="primary" (click)="closeWindow()">
            Close Window
          </button>
        </div>
      }
    </div>
  `,
  styles: [`
    .callback-container {
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 100vh;
      padding: 24px;
      background: var(--mat-app-background-color, #fafafa);
    }

    .loading-state,
    .error-state,
    .success-state {
      text-align: center;
      max-width: 400px;
      padding: 40px;
      background: var(--mat-app-surface, white);
      border-radius: 8px;
      box-shadow: 0 2px 10px rgba(0, 0, 0, 0.1);
    }

    .status-icon {
      font-size: 64px;
      width: 64px;
      height: 64px;
      margin-bottom: 16px;
    }

    .status-icon.success {
      color: #4CAF50;
    }

    .status-icon.error {
      color: #f44336;
    }

    h2 {
      margin: 16px 0 8px;
      color: var(--mat-app-text-color, #333);
    }

    p {
      color: var(--mat-app-secondary-text-color, #666);
      margin: 8px 0;
    }

    .hint {
      font-size: 0.9em;
      opacity: 0.8;
    }

    button {
      margin-top: 24px;
    }

    mat-spinner {
      margin: 0 auto 24px;
    }
  `]
})
export class OAuthCallbackComponent implements OnInit {
  loading = signal(true);
  error = signal<string | null>(null);
  serverName = signal<string>('the MCP server');

  constructor(
    private route: ActivatedRoute,
    private oauthService: McpOAuthService
  ) {}

  ngOnInit(): void {
    this.processCallback();
  }

  private async processCallback(): Promise<void> {
    const params = this.route.snapshot.queryParams;
    
    // Check for error from OAuth provider
    if (params['error']) {
      this.error.set(params['error_description'] || params['error']);
      this.loading.set(false);
      this.notifyParent(false);
      return;
    }

    const code = params['code'];
    const state = params['state'];

    if (!code || !state) {
      this.error.set('Missing authorization code or state parameter');
      this.loading.set(false);
      this.notifyParent(false);
      return;
    }

    try {
      const result = await this.oauthService.processCallback(code, state);
      
      if (result.success) {
        this.serverName.set(result.serverName || 'the MCP server');
        this.loading.set(false);
        this.notifyParent(true, result.serverName);
      } else {
        this.error.set(result.error || 'Authentication failed');
        this.loading.set(false);
        this.notifyParent(false);
      }
    } catch (e) {
      this.error.set('An unexpected error occurred');
      this.loading.set(false);
      this.notifyParent(false);
    }
  }

  private notifyParent(success: boolean, serverName?: string | null): void {
    if (window.opener) {
      window.opener.postMessage({
        type: 'oauth-callback',
        success,
        server: serverName || ''
      }, '*');
    }
  }

  closeWindow(): void {
    window.close();
  }
}
