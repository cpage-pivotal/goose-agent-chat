import { Component, computed, input, output, signal, OnInit } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ChatService, GooseConfig, SkillInfo, McpServerInfo } from '../../services/chat.service';
import { McpOAuthService } from '../../services/mcp-oauth.service';

@Component({
  selector: 'app-config-panel',
  standalone: true,
  imports: [
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatProgressSpinnerModule
  ],
  templateUrl: './config-panel.component.html',
  styleUrl: './config-panel.component.scss'
})
export class ConfigPanelComponent implements OnInit {
  collapsed = input<boolean>(false);
  collapseToggle = output<void>();
  
  private _config = signal<GooseConfig | null>(null);
  private _loading = signal<boolean>(true);
  private _error = signal<string | null>(null);
  private _connectingServer = signal<string | null>(null);
  
  readonly config = this._config.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();
  readonly connectingServer = this._connectingServer.asReadonly();
  
  readonly skills = computed(() => this._config()?.skills ?? []);
  readonly mcpServers = computed(() => this._config()?.mcpServers ?? []);
  readonly hasSkills = computed(() => this.skills().length > 0);
  readonly hasMcpServers = computed(() => this.mcpServers().length > 0);
  readonly hasContent = computed(() => this.hasSkills() || this.hasMcpServers());

  constructor(
    private chatService: ChatService,
    private oauthService: McpOAuthService
  ) {}

  ngOnInit(): void {
    this.loadConfig();
  }

  private async loadConfig(): Promise<void> {
    this._loading.set(true);
    this._error.set(null);
    
    try {
      const config = await this.chatService.getConfig();
      this._config.set(config);
      if (config.error) {
        this._error.set(config.error);
      }
    } catch (e) {
      this._error.set('Failed to load configuration');
      console.error('Error loading config:', e);
    } finally {
      this._loading.set(false);
    }
  }

  toggleCollapse(): void {
    this.collapseToggle.emit();
  }

  getSkillIcon(skill: SkillInfo): string {
    switch (skill.source) {
      case 'git': return 'cloud_download';
      case 'file': return 'folder';
      default: return 'description';
    }
  }

  getSkillSourceLabel(skill: SkillInfo): string {
    switch (skill.source) {
      case 'git': return 'Git';
      case 'file': return 'File';
      default: return 'Inline';
    }
  }

  getSkillTooltip(skill: SkillInfo): string {
    // Prioritize description for tooltip
    if (skill.description) {
      return skill.description;
    }
    // Fall back to source info if no description
    if (skill.repository) {
      return `${skill.repository}${skill.branch ? ` (${skill.branch})` : ''}${skill.path ? ` → ${skill.path}` : ''}`;
    }
    return skill.name;
  }

  getMcpServerIcon(server: McpServerInfo): string {
    return server.type === 'streamable_http' ? 'cloud' : 'terminal';
  }

  getMcpServerTypeLabel(server: McpServerInfo): string {
    return server.type === 'streamable_http' ? 'HTTP' : 'Stdio';
  }

  getMcpServerTooltip(server: McpServerInfo): string {
    if (server.url) {
      return server.url;
    }
    if (server.command) {
      const args = server.args?.join(' ') || '';
      return `${server.command} ${args}`.trim();
    }
    return server.name;
  }

  getMcpServerDetail(server: McpServerInfo): string {
    if (server.url) {
      // Show just the host for HTTP servers
      try {
        const url = new URL(server.url);
        return url.host;
      } catch {
        return server.url;
      }
    }
    if (server.command) {
      return server.command;
    }
    return '';
  }

  /**
   * Check if a server requires OAuth authentication.
   */
  requiresAuth(server: McpServerInfo): boolean {
    return server.requiresAuth === true;
  }

  /**
   * Check if a server is currently authenticated.
   */
  isAuthenticated(server: McpServerInfo): boolean {
    return this.oauthService.isAuthenticated(server.name);
  }

  /**
   * Check if a server is currently in the process of connecting.
   */
  isConnecting(server: McpServerInfo): boolean {
    return this._connectingServer() === server.name;
  }

  /**
   * Initiate OAuth connection for a server.
   */
  async connectServer(server: McpServerInfo): Promise<void> {
    const session = this.chatService.getCurrentSession();
    if (!session) {
      console.warn('No active session for OAuth');
      return;
    }

    this._connectingServer.set(server.name);
    
    try {
      const success = await this.oauthService.initiateAuth(server.name, session.sessionId);
      if (success) {
        console.log(`Successfully connected to ${server.name}`);
      } else {
        console.warn(`Failed to connect to ${server.name}`);
      }
    } catch (e) {
      console.error(`Error connecting to ${server.name}:`, e);
    } finally {
      this._connectingServer.set(null);
    }
  }

  /**
   * Disconnect from a server.
   */
  async disconnectServer(server: McpServerInfo): Promise<void> {
    const session = this.chatService.getCurrentSession();
    if (!session) {
      return;
    }

    try {
      await this.oauthService.disconnect(server.name, session.sessionId);
      console.log(`Disconnected from ${server.name}`);
    } catch (e) {
      console.error(`Error disconnecting from ${server.name}:`, e);
    }
  }
}
