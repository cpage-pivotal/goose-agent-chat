import { Injectable, signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  streaming?: boolean;
}

export interface SessionInfo {
  sessionId: string;
  createdAt: Date;
  provider?: string;
  model?: string;
}

export interface HealthInfo {
  available: boolean;
  version: string;
  provider: string;
  model: string;
  /** Source of model configuration: "genai-service" or "environment" */
  source?: string;
  message?: string;
}

export interface ActivityEvent {
  id: string;
  timestamp: Date;
  type: 'tool_request' | 'tool_response' | 'notification' | 'delegation_required';
  toolName?: string;
  extensionId?: string;
  arguments?: Record<string, unknown>;
  status: 'running' | 'completed' | 'error' | 'info' | 'action_required';
  message?: string;
  /** Target system that requires delegation (for delegation_required events) */
  targetSystem?: string;
}

export interface TodoItem {
  id: string;
  content: string;
  status: 'pending' | 'in_progress' | 'completed' | 'cancelled';
}

export interface SkillInfo {
  name: string;
  description?: string;
  /** Source type: "inline", "file", or "git" */
  source: string;
  path?: string;
  repository?: string;
  branch?: string;
}

export interface McpServerInfo {
  name: string;
  /** Transport type: "stdio" or "streamable_http" */
  type: string;
  url?: string;
  command?: string;
  args?: string[];
  /** Whether this server requires OAuth authentication */
  requiresAuth?: boolean;
  /** Whether the user is currently authenticated with this server */
  authenticated?: boolean;
}

export interface GooseConfig {
  provider?: string;
  model?: string;
  skills: SkillInfo[];
  mcpServers: McpServerInfo[];
  error?: string;
}

@Injectable({
  providedIn: 'root'
})
export class ChatService {
  private readonly apiUrl = '/api/chat';
  private currentSession = signal<SessionInfo | null>(null);
  
  // Activity events stream - emits tool calls and notifications
  private activitySubject = new Subject<ActivityEvent>();
  readonly activities$ = this.activitySubject.asObservable();
  
  // Signal to track current activities for the activity panel
  private _activities = signal<ActivityEvent[]>([]);
  readonly activities = this._activities.asReadonly();
  
  // Signal to track todo items parsed from todo__todo_write tool calls
  private _todos = signal<TodoItem[]>([]);
  readonly todos = this._todos.asReadonly();

  /**
   * Get the current active session
   */
  getCurrentSession(): SessionInfo | null {
    return this.currentSession();
  }

  /**
   * Clear all activities (typically when starting a new message).
   * Note: Todos are NOT cleared here - they persist across messages within a session
   * and are only updated when a new todo_write event arrives.
   */
  clearActivities(): void {
    this._activities.set([]);
  }
  
  /**
   * Clear todos (called when starting a new session)
   */
  clearTodos(): void {
    this._todos.set([]);
  }

  /**
   * Parse markdown checklist content from todo__todo_write into TodoItem array.
   * Handles formats:
   * - [ ] pending task
   * - [x] completed task  
   * - [-] in-progress or cancelled task
   */
  private parseTodoContent(content: string): TodoItem[] {
    const lines = content.split('\n');
    const todos: TodoItem[] = [];
    
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed.startsWith('-')) continue;
      
      // Match patterns: - [ ] text, - [x] text, - [-] text
      const match = trimmed.match(/^-\s*\[(.)\]\s*(.+)$/);
      if (match) {
        const marker = match[1].toLowerCase();
        const taskContent = match[2].trim();
        
        let status: TodoItem['status'];
        if (marker === 'x') {
          status = 'completed';
        } else if (marker === '-') {
          status = 'in_progress';
        } else {
          status = 'pending';
        }
        
        todos.push({
          id: `todo-${todos.length}`,
          content: taskContent,
          status
        });
      }
    }
    
    return todos;
  }

  /**
   * Create a new conversation session
   */
  async createSession(
    provider?: string, 
    model?: string,
    timeoutMinutes?: number
  ): Promise<string> {
    const body: Record<string, unknown> = {};
    if (provider) body['provider'] = provider;
    if (model) body['model'] = model;
    if (timeoutMinutes) body['sessionInactivityTimeoutMinutes'] = timeoutMinutes;

    const response = await fetch(`${this.apiUrl}/sessions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: Object.keys(body).length > 0 ? JSON.stringify(body) : undefined
    });

    if (response.status === 401 || response.status === 403) {
      throw new Error('AUTH_EXPIRED');
    }

    if (!response.ok) {
      throw new Error(`Failed to create session: ${response.status}`);
    }

    const result = await response.json();
    if (!result.success || !result.sessionId) {
      throw new Error(result.message || 'Failed to create session');
    }

    this.currentSession.set({
      sessionId: result.sessionId,
      createdAt: new Date(),
      provider,
      model
    });

    console.log('Created new Goose session:', result.sessionId);
    return result.sessionId;
  }

  /**
   * Send a message to the current session with SSE streaming.
   * 
   * Uses the native browser EventSource API for better handling of proxy buffering
   * and chunked encoding (especially important for Cloud Foundry's Go Router).
   * 
   * Uses token-level streaming from Goose CLI's --output-format stream-json.
   * Each emitted value is a single token as it arrives from the LLM.
   * 
   * SSE Events handled:
   * - `token`: Individual tokens (emitted to observer)
   * - `complete`: Stream completion with token count
   * - `status`: Processing status messages (logged)
   * - `error`: Error events
   */
  sendMessage(message: string, sessionId: string): Observable<string> {
    return new Observable(observer => {
      // Use GET endpoint with EventSource for better streaming support
      // EventSource handles proxy buffering and chunked encoding better than fetch
      const encodedMessage = encodeURIComponent(message);
      const url = `${this.apiUrl}/sessions/${sessionId}/stream?message=${encodedMessage}`;
      
      const eventSource = new EventSource(url);
      let streamCompleted = false;

      // Handle token events - individual tokens as they arrive
      // Tokens are JSON-encoded strings to preserve whitespace (SSE strips leading spaces)
      eventSource.addEventListener('token', (event: MessageEvent) => {
        const data = event.data;
        if (data && data.length > 0) {
          try {
            // Parse JSON string to get the actual token with preserved whitespace
            const token = JSON.parse(data);
            if (token && token.length > 0) {
              observer.next(token);
            }
          } catch {
            // Fallback: use raw data if not valid JSON
            observer.next(data);
          }
        }
      });

      // Handle activity events - tool calls and notifications
      eventSource.addEventListener('activity', (event: MessageEvent) => {
        try {
          const activityData = JSON.parse(event.data);
          
          const activity: ActivityEvent = {
            id: activityData.id,
            timestamp: new Date(activityData.timestamp || Date.now()),
            type: activityData.type,
            toolName: activityData.toolName,
            extensionId: activityData.extensionId,
            arguments: activityData.arguments,
            status: activityData.status,
            message: activityData.message
          };
          
          // Check if this is a todo_write tool call
          const isTodoWrite = activity.toolName === 'todo_write' && 
                              activity.extensionId === 'todo';
          
          if (isTodoWrite && activity.arguments?.['content']) {
            // Parse the todo content and update todos signal
            const todoContent = activity.arguments['content'] as string;
            const parsedTodos = this.parseTodoContent(todoContent);
            this._todos.set(parsedTodos);
            // Don't add todo_write to the regular activities list
          } else {
            // Update activity in the list or add new one
            this._activities.update(activities => {
              // For tool responses, update the existing tool request
              if (activity.type === 'tool_response') {
                // Check if this is a response to a todo_write (skip it)
                const existingActivity = activities.find(a => a.id === activity.id);
                if (existingActivity?.toolName === 'todo_write') {
                  return activities;
                }
                return activities.map(a => 
                  a.id === activity.id ? { ...a, status: activity.status } : a
                );
              }
              // For new activities, add to the list
              return [...activities, activity];
            });
          }
          
          this.activitySubject.next(activity);
        } catch (e) {
          console.warn('Failed to parse activity event:', event.data, e);
        }
      });

      // Handle delegation_required events — user needs to authorize in the broker UI
      eventSource.addEventListener('delegation_required', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data);
          const activity: ActivityEvent = {
            id: `delegation-${data.targetSystem}-${Date.now()}`,
            timestamp: new Date(),
            type: 'delegation_required',
            status: 'action_required',
            targetSystem: data.targetSystem,
            message: `Access to ${data.targetSystem} required. Authorize in the Credential Broker.`
          };
          this._activities.update(activities => [...activities, activity]);
          this.activitySubject.next(activity);
        } catch (e) {
          console.warn('Failed to parse delegation_required event:', event.data, e);
        }
      });

      // Handle completion event
      eventSource.addEventListener('complete', () => {
        streamCompleted = true;
        eventSource.close();
        observer.complete();
      });

      // Handle error events explicitly sent by the server (event: error\ndata: ...)
      // Connection-level errors (401, network failure) also fire this event but with
      // empty data — those are handled by onerror below.
      eventSource.addEventListener('error', (event: MessageEvent) => {
        if (event.data) {
          eventSource.close();
          observer.error(new Error(event.data));
        }
      });

      // Handle connection errors
      // streamCompleted distinguishes a normal post-complete close (readyState=CLOSED)
      // from a fatal HTTP error like 401 (also readyState=CLOSED per EventSource spec)
      eventSource.onerror = async () => {
        if (streamCompleted) {
          observer.complete();
          return;
        }
        if (eventSource.readyState !== EventSource.CLOSED) {
          eventSource.close();
        }
        try {
          const authCheck = await fetch('/auth/status', { credentials: 'same-origin' });
          if (authCheck.status === 401 || authCheck.status === 403) {
            observer.error(new Error('AUTH_EXPIRED'));
            return;
          }
        } catch { /* ignore auth check failure */ }
        observer.error(new Error('Connection to server failed'));
      };

      // Cleanup on unsubscribe
      return () => {
        console.log('[SSE] Closing connection');
        eventSource.close();
      };
    });
  }

  /**
   * Close the current conversation session
   */
  async closeSession(sessionId: string): Promise<void> {
    try {
      const response = await fetch(`${this.apiUrl}/sessions/${sessionId}`, {
        method: 'DELETE'
      });

      if (!response.ok) {
        console.warn(`Failed to close session ${sessionId}: ${response.status}`);
      }

      const currentSessionInfo = this.currentSession();
      if (currentSessionInfo && currentSessionInfo.sessionId === sessionId) {
        this.currentSession.set(null);
      }

      console.log('Closed Goose session:', sessionId);
    } catch (error) {
      console.error('Error closing session:', error);
      throw error;
    }
  }

  /**
   * Check if a session is active
   */
  async checkSessionStatus(sessionId: string): Promise<boolean> {
    try {
      const response = await fetch(`${this.apiUrl}/sessions/${sessionId}/status`);
      if (!response.ok) {
        return false;
      }
      const result = await response.json();
      return result.active;
    } catch (error) {
      console.error('Error checking session status:', error);
      return false;
    }
  }

  /**
   * Check Goose health status
   */
  checkHealth(): Promise<HealthInfo> {
    return fetch(`${this.apiUrl}/health`)
      .then(response => response.json())
      .catch(error => {
        console.error('Health check failed:', error);
        return { 
          available: false, 
          version: 'unknown',
          provider: 'unknown',
          model: 'unknown',
          source: 'unknown',
          message: 'Health check endpoint not reachable' 
        };
      });
  }

  /**
   * Get Goose configuration including skills and MCP servers.
   */
  getConfig(): Promise<GooseConfig> {
    return fetch('/api/config')
      .then(response => response.json())
      .catch(error => {
        console.error('Config fetch failed:', error);
        return {
          skills: [],
          mcpServers: [],
          error: 'Configuration endpoint not reachable'
        };
      });
  }
}

