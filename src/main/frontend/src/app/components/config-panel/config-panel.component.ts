import { Component, computed, inject, input, output, signal, OnInit } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { ChatService, GooseConfig, SkillInfo, McpServerInfo } from '../../services/chat.service';
import { McpOAuthService } from '../../services/mcp-oauth.service';
import { SecurityDiagramDialogComponent } from '../security-diagram-dialog/security-diagram-dialog.component';

@Component({
  selector: 'app-config-panel',
  standalone: true,
  imports: [
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    MatDialogModule,
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
  
  readonly config = this._config.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();
  
  readonly skills = computed(() => this._config()?.skills ?? []);
  readonly mcpServers = computed(() => this._config()?.mcpServers ?? []);
  readonly hasSkills = computed(() => this.skills().length > 0);
  readonly hasMcpServers = computed(() => this.mcpServers().length > 0);
  readonly hasContent = computed(() => this.hasSkills() || this.hasMcpServers());

  readonly isBrokerMode = computed(() => this.oauthService.isBrokerMode);

  private dialog = inject(MatDialog);

  constructor(
    private chatService: ChatService,
    private oauthService: McpOAuthService
  ) {}

  ngOnInit(): void {
    this.loadConfig();
    this.oauthService.checkBrokerConfig();
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
    if (skill.description) {
      return skill.description;
    }
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

  requiresAuth(server: McpServerInfo): boolean {
    return server.requiresAuth === true;
  }

  openSecurityDiagram(): void {
    this.dialog.open(SecurityDiagramDialogComponent, {
      width: '1100px',
      maxWidth: '95vw',
    });
  }

  openBrokerGrants(): void {
    this.oauthService.openBrokerGrants();
  }
}
