import { Component, signal, effect, ViewChild, ElementRef, computed } from '@angular/core';

import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MarkdownComponent } from 'ngx-markdown';
import { ChatService, ChatMessage, HealthInfo } from '../../services/chat.service';
import { ActivityPanelComponent } from '../activity-panel/activity-panel.component';
import { ConfigPanelComponent } from '../config-panel/config-panel.component';
import { ProviderSelectorComponent } from '../provider-selector/provider-selector.component';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatTooltipModule,
    MatSnackBarModule,
    MarkdownComponent,
    ActivityPanelComponent,
    ConfigPanelComponent,
    ProviderSelectorComponent
  ],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.scss'
})
export class ChatComponent {
  @ViewChild('messagesContainer') private messagesContainer!: ElementRef;
  @ViewChild(ProviderSelectorComponent) private providerSelector!: ProviderSelectorComponent;
  
  protected messages = signal<ChatMessage[]>([]);
  protected userInput = signal('');
  protected isStreaming = signal(false);
  protected healthInfo = signal<HealthInfo | null>(null);
  protected sessionId = signal<string | null>(null);
  protected isCreatingSession = signal(false);
  protected activityPanelCollapsed = signal(false);
  protected configPanelCollapsed = signal(false);

  // Tracks the provider/model actually used for the currently active session.
  // This is set when we create the session (based on the dropdown selection).
  protected activeSessionProviderLabel = signal<string | null>(null);
  protected activeSessionModelLabel = signal<string | null>(null);
  
  // Expose activities and todos from the service
  protected activities = computed(() => this.chatService.activities());
  protected todos = computed(() => this.chatService.todos());

  constructor(
    private chatService: ChatService,
    private snackBar: MatSnackBar
  ) {
    // Check Goose availability on init
    this.chatService.checkHealth().then(health => {
      this.healthInfo.set(health);
    });

    // Auto-scroll when messages update with smooth animation
    effect(() => {
      this.messages();
      // Use requestAnimationFrame for smoother scroll animation
      requestAnimationFrame(() => {
        setTimeout(() => this.scrollToBottom(), 50);
      });
    });

    // Don't auto-create session - let user select provider/model first
    // User can manually start conversation after selecting provider/model
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

  protected get activeConversationProviderLabel(): string {
    // If GenAI service is bound, it takes precedence and may ignore UI selection.
    // In that case, prefer the health-indicated provider/model.
    if (this.isGenaiService) {
      return this.gooseProvider;
    }
    return this.activeSessionProviderLabel() ?? this.gooseProvider;
  }

  protected get activeConversationModelLabel(): string {
    if (this.isGenaiService) {
      return this.gooseModel;
    }
    return this.activeSessionModelLabel() ?? this.gooseModel;
  }

  /**
   * Start a new conversation session
   */
  protected async startNewConversation(): Promise<void> {
    if (this.isCreatingSession()) {
      return;
    }

    this.isCreatingSession.set(true);

    try {
      // Close existing session if any
      const currentSessionId = this.sessionId();
      if (currentSessionId) {
        await this.chatService.closeSession(currentSessionId);
      }

      // Get selected provider/model from provider selector
      const selectedProvider = this.providerSelector?.getSelectedProvider();
      const selectedModel = this.providerSelector?.getSelectedModel();
      const selectedProviderLabel = this.providerSelector?.getProviderDisplayName() || selectedProvider || null;
      const selectedModelLabel = this.providerSelector?.getModelDisplayName() || selectedModel || null;
      
      // Create new session with selected provider/model
      const newSessionId = await this.chatService.createSession(selectedProvider || undefined, selectedModel || undefined);
      this.sessionId.set(newSessionId);

      // Persist what was selected for this session (so header can reflect it).
      // If GenAI service is bound, keep using health-indicated model/provider.
      if (!this.isGenaiService) {
        this.activeSessionProviderLabel.set(selectedProviderLabel);
        this.activeSessionModelLabel.set(selectedModelLabel);
      } else {
        this.activeSessionProviderLabel.set(null);
        this.activeSessionModelLabel.set(null);
      }
      
      // Clear messages and todos for new conversation
      this.messages.set([]);
      this.chatService.clearTodos();
      this.chatService.clearActivities();
      
      this.snackBar.open('New Goose conversation started', 'Close', {
        duration: 2000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
      
      console.log('Started new conversation with session:', newSessionId);
    } catch (error) {
      console.error('Failed to start new conversation:', error);
      this.snackBar.open('Failed to start conversation', 'Close', {
        duration: 3000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
      this.sessionId.set(null);
    } finally {
      this.isCreatingSession.set(false);
    }
  }

  /**
   * End the current conversation
   */
  protected async endConversation(): Promise<void> {
    const currentSessionId = this.sessionId();
    if (!currentSessionId) {
      return;
    }

    try {
      await this.chatService.closeSession(currentSessionId);
      this.sessionId.set(null);
      this.activeSessionProviderLabel.set(null);
      this.activeSessionModelLabel.set(null);
      this.messages.set([]);
      this.chatService.clearTodos();
      this.chatService.clearActivities();
      
      this.snackBar.open('Conversation ended', 'Close', {
        duration: 2000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
    } catch (error) {
      console.error('Failed to end conversation:', error);
      this.snackBar.open('Failed to end conversation', 'Close', {
        duration: 3000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
    }
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
    
    if (!prompt || this.isStreaming() || !currentSessionId) {
      return;
    }

    // Clear activities for new message
    this.chatService.clearActivities();
    
    // Auto-expand activity panel when streaming starts
    this.activityPanelCollapsed.set(false);

    // Add user message
    const userMessage: ChatMessage = {
      role: 'user',
      content: prompt,
      timestamp: new Date()
    };
    this.messages.update(msgs => [...msgs, userMessage]);
    this.userInput.set('');
    this.isStreaming.set(true);

    // Add assistant message placeholder
    const assistantMessage: ChatMessage = {
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      streaming: true
    };
    this.messages.update(msgs => [...msgs, assistantMessage]);

    // Stream the response with token-level updates
    this.chatService.sendMessage(prompt, currentSessionId).subscribe({
      next: (token: string) => {
        // Append token directly - no newline needed for token-level streaming
        this.messages.update(msgs => {
          const lastMsg = msgs[msgs.length - 1];
          if (lastMsg.role === 'assistant') {
            return [
              ...msgs.slice(0, -1),
              {
                ...lastMsg,
                content: lastMsg.content + token
              }
            ];
          }
          return msgs;
        });
      },
      error: (error) => {
        console.error('Chat error:', error);
        
        const errorMessage = error.message || 'Failed to get response from Goose.';
        
        // Check if session expired
        if (errorMessage.includes('Session not found') || errorMessage.includes('expired')) {
          this.snackBar.open('Session expired. Starting new conversation...', 'Close', {
            duration: 3000,
            horizontalPosition: 'center',
            verticalPosition: 'bottom'
          });
          
          // Remove the failed assistant message
          this.messages.update(msgs => msgs.slice(0, -1));
          
          // Start new conversation and resend message
          this.startNewConversation().then(() => {
            // Resend the message after new session is created
            if (this.sessionId()) {
              this.userInput.set(prompt);
              setTimeout(() => this.sendMessage(), 500);
            }
          });
        } else {
          this.messages.update(msgs => {
            const lastMsg = msgs[msgs.length - 1];
            if (lastMsg.role === 'assistant' && lastMsg.streaming) {
              return [
                ...msgs.slice(0, -1),
                {
                  ...lastMsg,
                  content: lastMsg.content || `Error: ${errorMessage}`,
                  streaming: false
                }
              ];
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
            return [
              ...msgs.slice(0, -1),
              {
                ...lastMsg,
                streaming: false
              }
            ];
          }
          return msgs;
        });
        this.isStreaming.set(false);
      }
    });
  }

  protected onKeyPress(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  protected onInputChange(event: Event): void {
    const target = event.target as HTMLTextAreaElement;
    this.userInput.set(target.value);
  }

  private scrollToBottom(): void {
    if (this.messagesContainer) {
      const element = this.messagesContainer.nativeElement;
      // Smooth scroll to bottom
      element.scrollTo({
        top: element.scrollHeight,
        behavior: 'smooth'
      });
    }
  }
}

