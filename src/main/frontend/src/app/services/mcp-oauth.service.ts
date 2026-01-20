import { Injectable, signal } from '@angular/core';

/**
 * OAuth authentication status for an MCP server.
 */
export interface McpAuthStatus {
  serverName: string;
  authenticated: boolean;
  message?: string;
}

/**
 * Response from initiating OAuth flow.
 */
export interface InitiateAuthResponse {
  authUrl: string | null;
  state: string | null;
  error: string | null;
}

/**
 * Response from OAuth callback processing.
 */
export interface CallbackResponse {
  success: boolean;
  serverName: string | null;
  error: string | null;
}

/**
 * Service for managing OAuth authentication with MCP servers.
 * 
 * Handles:
 * - Checking authentication status
 * - Initiating OAuth flows (opens popup for authorization)
 * - Processing OAuth callbacks
 * - Disconnecting (revoking tokens)
 */
@Injectable({
  providedIn: 'root'
})
export class McpOAuthService {
  private readonly apiUrl = '/api/mcp';
  
  // Track authentication status per server
  private _authStatus = signal<Map<string, boolean>>(new Map());
  readonly authStatus = this._authStatus.asReadonly();

  // Track pending OAuth operations
  private _pendingAuth = signal<string | null>(null);
  readonly pendingAuth = this._pendingAuth.asReadonly();

  /**
   * Check if a session is authenticated with an MCP server.
   */
  async checkAuthStatus(serverName: string, sessionId: string): Promise<McpAuthStatus> {
    try {
      const response = await fetch(
        `${this.apiUrl}/${encodeURIComponent(serverName)}/auth/status?sessionId=${encodeURIComponent(sessionId)}`
      );
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      
      const status: McpAuthStatus = await response.json();
      
      // Update local status
      this._authStatus.update(map => {
        const newMap = new Map(map);
        newMap.set(serverName, status.authenticated);
        return newMap;
      });
      
      return status;
    } catch (error) {
      console.error(`Failed to check auth status for ${serverName}:`, error);
      return {
        serverName,
        authenticated: false,
        message: 'Failed to check authentication status'
      };
    }
  }

  /**
   * Check if a server is currently authenticated.
   */
  isAuthenticated(serverName: string): boolean {
    return this._authStatus().get(serverName) ?? false;
  }

  /**
   * Initiate OAuth authentication for an MCP server.
   * Opens a popup window for the OAuth flow.
   */
  async initiateAuth(serverName: string, sessionId: string): Promise<boolean> {
    console.log(`Initiating OAuth for ${serverName}`);
    this._pendingAuth.set(serverName);
    
    try {
      const response = await fetch(
        `${this.apiUrl}/${encodeURIComponent(serverName)}/auth/initiate?sessionId=${encodeURIComponent(sessionId)}`
      );
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      
      const result: InitiateAuthResponse = await response.json();
      
      if (result.error) {
        console.error(`OAuth initiation error: ${result.error}`);
        this._pendingAuth.set(null);
        return false;
      }
      
      if (!result.authUrl) {
        console.error('No auth URL returned');
        this._pendingAuth.set(null);
        return false;
      }
      
      // Open popup for OAuth flow
      const popup = this.openAuthPopup(result.authUrl, serverName);
      
      if (!popup) {
        console.error('Failed to open popup window');
        this._pendingAuth.set(null);
        return false;
      }
      
      // Wait for popup to complete
      const success = await this.waitForPopupClose(popup, serverName, sessionId);
      
      this._pendingAuth.set(null);
      return success;
      
    } catch (error) {
      console.error(`Failed to initiate OAuth for ${serverName}:`, error);
      this._pendingAuth.set(null);
      return false;
    }
  }

  /**
   * Open a popup window for OAuth authorization.
   */
  private openAuthPopup(authUrl: string, serverName: string): Window | null {
    const width = 600;
    const height = 700;
    const left = window.screenX + (window.outerWidth - width) / 2;
    const top = window.screenY + (window.outerHeight - height) / 2;
    
    const features = `width=${width},height=${height},left=${left},top=${top},toolbar=no,menubar=no,location=yes,status=yes`;
    
    return window.open(authUrl, `oauth-${serverName}`, features);
  }

  /**
   * Wait for the OAuth popup to close and check result.
   */
  private waitForPopupClose(popup: Window, serverName: string, sessionId: string): Promise<boolean> {
    return new Promise((resolve) => {
      // Listen for postMessage from popup
      const messageHandler = (event: MessageEvent) => {
        if (event.data?.type === 'oauth-callback') {
          window.removeEventListener('message', messageHandler);
          
          if (event.data.success) {
            this._authStatus.update(map => {
              const newMap = new Map(map);
              newMap.set(serverName, true);
              return newMap;
            });
            resolve(true);
          } else {
            resolve(false);
          }
        }
      };
      
      window.addEventListener('message', messageHandler);
      
      // Also poll for popup close (in case postMessage fails)
      const pollInterval = setInterval(async () => {
        if (popup.closed) {
          clearInterval(pollInterval);
          window.removeEventListener('message', messageHandler);
          
          // Check auth status after popup closes
          const status = await this.checkAuthStatus(serverName, sessionId);
          resolve(status.authenticated);
        }
      }, 500);
      
      // Timeout after 5 minutes
      setTimeout(() => {
        clearInterval(pollInterval);
        window.removeEventListener('message', messageHandler);
        if (!popup.closed) {
          popup.close();
        }
        resolve(false);
      }, 5 * 60 * 1000);
    });
  }

  /**
   * Process OAuth callback (called from callback component if needed).
   */
  async processCallback(code: string, state: string): Promise<CallbackResponse> {
    try {
      const response = await fetch('/api/mcp/auth/callback', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ code, state })
      });
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      
      const result: CallbackResponse = await response.json();
      
      if (result.success && result.serverName) {
        this._authStatus.update(map => {
          const newMap = new Map(map);
          newMap.set(result.serverName!, true);
          return newMap;
        });
      }
      
      return result;
    } catch (error) {
      console.error('Failed to process OAuth callback:', error);
      return {
        success: false,
        serverName: null,
        error: 'Failed to process callback'
      };
    }
  }

  /**
   * Disconnect (revoke tokens) from an MCP server.
   */
  async disconnect(serverName: string, sessionId: string): Promise<boolean> {
    try {
      const response = await fetch(
        `${this.apiUrl}/${encodeURIComponent(serverName)}/auth/disconnect?sessionId=${encodeURIComponent(sessionId)}`,
        { method: 'POST' }
      );
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      
      // Update local status
      this._authStatus.update(map => {
        const newMap = new Map(map);
        newMap.set(serverName, false);
        return newMap;
      });
      
      return true;
    } catch (error) {
      console.error(`Failed to disconnect from ${serverName}:`, error);
      return false;
    }
  }

  /**
   * Clear all authentication status (e.g., on session end).
   */
  clearAll(): void {
    this._authStatus.set(new Map());
    this._pendingAuth.set(null);
  }
}
