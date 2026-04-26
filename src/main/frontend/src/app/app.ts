import { Component, OnInit, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { ChatComponent } from './components/chat/chat.component';
import { LoginComponent } from './components/login/login.component';
import { AuthService, AuthMode } from './services/auth.service';

@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    MatToolbarModule,
    MatIconModule,
    MatButtonModule,
    MatMenuModule,
    ChatComponent,
    LoginComponent
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements OnInit {
  protected readonly title = signal('Goose Agent Chat');
  protected readonly displayName = signal<string | null>(null);
  protected readonly isAuthenticated = signal(false);
  protected readonly mode = signal<AuthMode | null>(null);
  protected readonly authLoaded = signal(false);

  constructor(private authService: AuthService) {}

  ngOnInit(): void {
    this.authService.checkAuthStatus().then(status => {
      this.isAuthenticated.set(status.authenticated);
      this.mode.set(status.mode);
      if (status.authenticated && status.displayName) {
        this.displayName.set(status.displayName);
      }
      this.authLoaded.set(true);

      // OAuth2 mode: bounce unauthenticated users to the SSO authorization endpoint.
      // Password mode: render the login form inline (handled in the template).
      if (!status.authenticated && status.mode === 'oauth2') {
        window.location.href = status.loginUrl;
      }
    });
  }

  logout(): void {
    this.authService.logout();
  }
}
