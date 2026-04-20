import { Component, computed, signal, inject, DestroyRef, OnInit, HostListener } from '@angular/core';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

const STEP_INTERVAL_MS = 850;
const MAX_STEPS = 8;

@Component({
  selector: 'app-security-diagram-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  templateUrl: './security-diagram-dialog.component.html',
  styleUrl: './security-diagram-dialog.component.scss',
})
export class SecurityDiagramDialogComponent implements OnInit {
  private readonly dialogRef = inject(MatDialogRef<SecurityDiagramDialogComponent>);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly visibleStep = signal(0);

  protected readonly flowSteps = [
    { label: 'SSO Token',          sub: "Agent holds user's SSO access token" },
    { label: 'Token Exchange',     sub: 'Agent → Broker over mTLS' },
    { label: 'Delegation JWT',     sub: 'Broker returns signed delegation token' },
    { label: 'Credential Request', sub: 'Agent presents mTLS cert + delegation' },
    { label: 'Validate',           sub: 'Broker runs three-layer checks' },
    { label: 'Verify Grant',       sub: 'DB lookup confirms active grant' },
    { label: 'Fetch',              sub: 'Broker retrieves credential from target' },
    { label: 'Return',             sub: 'ResourceAccessToken returned to agent' },
  ];

  protected readonly currentStepLabel = computed(() => {
    const s = this.visibleStep();
    return s > 0 ? `Step ${s}: ${this.flowSteps[s - 1].label}` : '';
  });

  private timers: ReturnType<typeof setTimeout>[] = [];

  ngOnInit(): void {
    this.startAnimation();
    this.destroyRef.onDestroy(() => this.clearTimers());
  }

  @HostListener('window:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'ArrowRight') {
      this.clearTimers();
      this.visibleStep.update(s => Math.min(s + 1, MAX_STEPS));
    } else if (event.key === 'ArrowLeft') {
      this.clearTimers();
      this.visibleStep.update(s => Math.max(s - 1, 0));
    } else if (event.key.toLowerCase() === 'r') {
      this.replay();
    }
  }

  goToStep(step: number): void {
    this.clearTimers();
    this.visibleStep.set(step);
  }

  replay(): void {
    this.clearTimers();
    this.visibleStep.set(0);
    setTimeout(() => this.startAnimation(), 50);
  }

  close(): void {
    this.dialogRef.close();
  }

  isActive(index: number): boolean {
    return this.visibleStep() === index + 1;
  }

  isDone(index: number): boolean {
    return this.visibleStep() > index + 1;
  }

  stepOpacity(step: number): number {
    return this.visibleStep() >= step ? 1 : 0;
  }

  lineDashOffset(step: number): number {
    return this.visibleStep() >= step ? 0 : 600;
  }

  isCheckOn(step: number): boolean {
    return this.visibleStep() >= step;
  }

  isEmphasized(step: number): boolean {
    return this.visibleStep() >= step;
  }

  private startAnimation(): void {
    for (let i = 1; i <= MAX_STEPS; i++) {
      const timer = setTimeout(() => this.visibleStep.set(i), 200 + i * STEP_INTERVAL_MS);
      this.timers.push(timer);
    }
  }

  private clearTimers(): void {
    this.timers.forEach(t => clearTimeout(t));
    this.timers = [];
  }
}
