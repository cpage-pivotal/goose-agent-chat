import { Component, signal, effect, ViewChild, ElementRef, computed } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';

import { MatButtonModule } from '@angular/material/button';
import { MatIconModule, MatIconRegistry } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MarkdownComponent } from 'ngx-markdown';
import { ChatService, ChatMessage, HealthInfo } from '../../services/chat.service';
import { ActivityPanelComponent } from '../activity-panel/activity-panel.component';
import { ConfigPanelComponent } from '../config-panel/config-panel.component';

interface MessageRun {
  role: 'user' | 'assistant';
  messages: ChatMessage[];
}

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSnackBarModule,
    MarkdownComponent,
    ActivityPanelComponent,
    ConfigPanelComponent
  ],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.scss'
})
export class ChatComponent {
  @ViewChild('messageStream') private messageStream!: ElementRef;

  protected messages = signal<ChatMessage[]>([]);
  protected userInput = signal('');
  protected isStreaming = signal(false);
  protected healthInfo = signal<HealthInfo | null>(null);
  protected sessionId = signal<string | null>(null);
  protected isCreatingSession = signal(false);
  protected activityPanelCollapsed = signal(false);
  protected configPanelCollapsed = signal(false);
  protected showNewMessagesPill = signal(false);
  protected composerFocused = signal(false);

  protected activities = computed(() => this.chatService.activities());
  protected todos = computed(() => this.chatService.todos());

  protected messageRuns = computed(() => {
    const msgs = this.messages();
    const runs: MessageRun[] = [];
    for (const msg of msgs) {
      const last = runs[runs.length - 1];
      if (last && last.role === msg.role) {
        last.messages.push(msg);
      } else {
        runs.push({ role: msg.role, messages: [msg] });
      }
    }
    return runs;
  });

  private isNearBottom = true;

  constructor(
    private chatService: ChatService,
    private snackBar: MatSnackBar,
    private matIconRegistry: MatIconRegistry,
    private domSanitizer: DomSanitizer
  ) {
    this.matIconRegistry.addSvgIcon(
      'goose',
      this.domSanitizer.bypassSecurityTrustResourceUrl('/goose-svgrepo-com.svg')
    );

    this.chatService.checkHealth().then(health => {
      this.healthInfo.set(health);
    });

    effect(() => {
      this.messages();
      requestAnimationFrame(() => {
        setTimeout(() => this.scrollToBottom(), 50);
      });
    });

    effect(() => {
      const health = this.healthInfo();
      if (health?.available && !this.sessionId() && !this.isCreatingSession()) {
        this.startNewConversation();
      }
    });
  }

  protected get gooseAvailable(): boolean {
    return this.healthInfo()?.available ?? false;
  }

  protected get gooseVersion(): string {
    return this.healthInfo()?.version ?? 'unknown';
  }

  protected get gooseProvider(): string {
    return this.healthInfo()?.provider ?? 'unknown';
  }

  protected get gooseModel(): string {
    return this.healthInfo()?.model ?? 'unknown';
  }

  protected get gooseMessage(): string {
    return this.healthInfo()?.message ?? '';
  }

  protected get gooseModelSource(): string {
    return this.healthInfo()?.source ?? 'unknown';
  }

  protected get isGenaiService(): boolean {
    return this.healthInfo()?.source === 'genai-service';
  }

  protected async startNewConversation(): Promise<void> {
    if (this.isCreatingSession()) return;

    this.isCreatingSession.set(true);
    try {
      const currentSessionId = this.sessionId();
      if (currentSessionId) {
        await this.chatService.closeSession(currentSessionId);
      }
      const newSessionId = await this.chatService.createSession();
      this.sessionId.set(newSessionId);
      this.messages.set([]);
      this.chatService.clearTodos();
      this.chatService.clearActivities();
      this.snackBar.open('New Goose conversation started', 'Close', {
        duration: 2000, horizontalPosition: 'center', verticalPosition: 'bottom'
      });
    } catch (error) {
      console.error('Failed to start new conversation:', error);
      this.snackBar.open('Failed to start conversation', 'Close', {
        duration: 3000, horizontalPosition: 'center', verticalPosition: 'bottom'
      });
      this.sessionId.set(null);
    } finally {
      this.isCreatingSession.set(false);
    }
  }

  protected async copySessionId(): Promise<void> {
    const id = this.sessionId();
    if (!id) return;
    try {
      await navigator.clipboard.writeText(id);
      this.snackBar.open('Session ID copied', 'Close', {
        duration: 1500, horizontalPosition: 'center', verticalPosition: 'bottom'
      });
    } catch {
      this.snackBar.open('Failed to copy session ID', 'Close', {
        duration: 3000, horizontalPosition: 'center', verticalPosition: 'bottom'
      });
    }
  }

  protected async copyMessage(content: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(content);
      this.snackBar.open('Copied', 'Close', {
        duration: 1500, horizontalPosition: 'center', verticalPosition: 'bottom'
      });
    } catch { /* ignore */ }
  }

  protected toggleActivityPanel(): void {
    this.activityPanelCollapsed.update(v => !v);
  }

  protected toggleConfigPanel(): void {
    this.configPanelCollapsed.update(v => !v);
  }

  protected sendMessage(): void {
    const prompt = this.userInput().trim();
    const currentSessionId = this.sessionId();
    if (!prompt || this.isStreaming() || !currentSessionId) return;

    this.chatService.clearActivities();
    this.activityPanelCollapsed.set(false);

    const userMessage: ChatMessage = { role: 'user', content: prompt, timestamp: new Date() };
    this.messages.update(msgs => [...msgs, userMessage]);
    this.userInput.set('');
    this.isStreaming.set(true);

    const assistantMessage: ChatMessage = { role: 'assistant', content: '', timestamp: new Date(), streaming: true };
    this.messages.update(msgs => [...msgs, assistantMessage]);

    this.chatService.sendMessage(prompt, currentSessionId).subscribe({
      next: (token: string) => {
        this.messages.update(msgs => {
          const lastMsg = msgs[msgs.length - 1];
          if (lastMsg.role === 'assistant') {
            return [...msgs.slice(0, -1), { ...lastMsg, content: lastMsg.content + token }];
          }
          return msgs;
        });
      },
      error: (error) => {
        console.error('Chat error:', error);
        const errorMessage = error.message || 'Failed to get response from Goose.';
        if (errorMessage.includes('Session not found') || errorMessage.includes('expired')) {
          this.snackBar.open('Session expired. Starting new conversation…', 'Close', {
            duration: 3000, horizontalPosition: 'center', verticalPosition: 'bottom'
          });
          this.messages.update(msgs => msgs.slice(0, -1));
          this.startNewConversation().then(() => {
            if (this.sessionId()) {
              this.userInput.set(prompt);
              setTimeout(() => this.sendMessage(), 500);
            }
          });
        } else {
          this.messages.update(msgs => {
            const lastMsg = msgs[msgs.length - 1];
            if (lastMsg.role === 'assistant' && lastMsg.streaming) {
              return [...msgs.slice(0, -1), { ...lastMsg, content: lastMsg.content || `Error: ${errorMessage}`, streaming: false }];
            }
            return msgs;
          });
        }
        this.isStreaming.set(false);
      },
      complete: () => {
        this.messages.update(msgs => {
          const lastMsg = msgs[msgs.length - 1];
          if (lastMsg.role === 'assistant') {
            return [...msgs.slice(0, -1), { ...lastMsg, streaming: false }];
          }
          return msgs;
        });
        this.isStreaming.set(false);
      }
    });
  }

  protected onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  protected onInputChange(event: Event): void {
    const target = event.target as HTMLTextAreaElement;
    this.userInput.set(target.value);
  }

  protected onStreamScroll(event: Event): void {
    const el = event.target as HTMLElement;
    this.isNearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
    if (this.isNearBottom) {
      this.showNewMessagesPill.set(false);
    }
  }

  protected scrollToBottom(force = false): void {
    const el = this.messageStream?.nativeElement;
    if (!el) return;
    if (force || this.isNearBottom) {
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
      this.showNewMessagesPill.set(false);
    } else {
      this.showNewMessagesPill.set(true);
    }
  }
}
