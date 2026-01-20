import { Routes } from '@angular/router';
import { OAuthCallbackComponent } from './components/oauth-callback/oauth-callback.component';

export const routes: Routes = [
  {
    path: 'oauth/callback',
    component: OAuthCallbackComponent,
    title: 'OAuth Callback'
  }
];

