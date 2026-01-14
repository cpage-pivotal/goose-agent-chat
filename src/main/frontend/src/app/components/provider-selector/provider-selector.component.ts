import { Component, signal, computed, effect, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatOptionModule } from '@angular/material/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { HttpClient } from '@angular/common/http';
import { inject } from '@angular/core';

interface Provider {
  name: string;
  displayName: string;
  modelCount: number;
}

interface Model {
  name: string;
  displayName: string;
}

interface ProvidersResponse {
  providers: Provider[];
  error: string | null;
}

interface ModelsResponse {
  models: Model[];
  error: string | null;
}

@Component({
  selector: 'app-provider-selector',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatFormFieldModule,
    MatSelectModule,
    MatOptionModule,
    MatProgressSpinnerModule
  ],
  templateUrl: './provider-selector.component.html',
  styleUrl: './provider-selector.component.scss'
})
export class ProviderSelectorComponent implements OnInit {
  private http = inject(HttpClient);

  // State signals
  availableProviders = signal<Provider[]>([]);
  availableModels = signal<Model[]>([]);
  selectedProvider = signal<string | null>(null);
  selectedModel = signal<string | null>(null);
  loadingProviders = signal<boolean>(false);
  loadingModels = signal<boolean>(false);
  error = signal<string | null>(null);

  // Computed values
  modelsForSelectedProvider = computed(() => {
    const provider = this.selectedProvider();
    if (!provider) {
      return [];
    }
    return this.availableModels();
  });

  // Effect to load models when provider changes
  private providerChangeEffect = effect(() => {
    const provider = this.selectedProvider();
    if (provider) {
      this.loadModels(provider);
    } else {
      this.availableModels.set([]);
      this.selectedModel.set(null);
    }
  });

  ngOnInit(): void {
    this.loadProviders();
  }

  loadProviders(): void {
    this.loadingProviders.set(true);
    this.error.set(null);

    this.http.get<ProvidersResponse>('/api/providers').subscribe({
      next: (response) => {
        if (response.error) {
          this.error.set(response.error);
          this.availableProviders.set([]);
        } else {
          this.availableProviders.set(response.providers);
          // Auto-select first provider if none selected
          if (!this.selectedProvider() && response.providers.length > 0) {
            this.selectedProvider.set(response.providers[0].name);
          }
        }
        this.loadingProviders.set(false);
      },
      error: (err) => {
        console.error('Failed to load providers:', err);
        this.error.set('Failed to load providers. Please try again.');
        this.availableProviders.set([]);
        this.loadingProviders.set(false);
      }
    });
  }

  loadModels(providerName: string): void {
    this.loadingModels.set(true);
    this.selectedModel.set(null); // Clear previous selection

    this.http.get<ModelsResponse>(`/api/providers/${providerName}/models`).subscribe({
      next: (response) => {
        if (response.error) {
          this.error.set(response.error);
          this.availableModels.set([]);
        } else {
          this.availableModels.set(response.models);
          // Auto-select first model if available
          if (response.models.length > 0) {
            this.selectedModel.set(response.models[0].name);
          }
        }
        this.loadingModels.set(false);
      },
      error: (err) => {
        console.error(`Failed to load models for ${providerName}:`, err);
        this.error.set(`Failed to load models for ${providerName}. Please try again.`);
        this.availableModels.set([]);
        this.loadingModels.set(false);
      }
    });
  }

  onProviderChange(providerName: string): void {
    this.selectedProvider.set(providerName);
    // Models will be loaded automatically by the effect
  }

  onModelChange(modelName: string): void {
    this.selectedModel.set(modelName);
  }

  getSelectedProvider(): string | null {
    return this.selectedProvider();
  }

  getSelectedModel(): string | null {
    return this.selectedModel();
  }

  getProviderDisplayName(): string {
    const provider = this.availableProviders().find(p => p.name === this.selectedProvider());
    return provider?.displayName || this.selectedProvider() || '';
  }

  getModelDisplayName(): string {
    const model = this.availableModels().find(m => m.name === this.selectedModel());
    return model?.displayName || this.selectedModel() || '';
  }
}
