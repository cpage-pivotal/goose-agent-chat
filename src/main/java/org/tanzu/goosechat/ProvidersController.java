package org.tanzu.goosechat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller for exposing available AI providers and models.
 * <p>
 * This controller provides endpoints for the frontend to discover
 * which providers and models are available for selection.
 * </p>
 */
@RestController
@RequestMapping("/api/providers")
@CrossOrigin(origins = "*")
public class ProvidersController {

    private final ProviderConfiguration providerConfiguration;

    public ProvidersController(@Autowired ProviderConfiguration providerConfiguration) {
        this.providerConfiguration = providerConfiguration;
    }

    /**
     * Get all available providers.
     * 
     * @return list of available providers (without API keys)
     */
    @GetMapping
    public ProvidersResponse getProviders() {
        List<ProviderConfiguration.ProviderInfo> providers = providerConfiguration.getAvailableProviders();
        
        List<ProviderSummary> providerSummaries = providers.stream()
            .map(p -> new ProviderSummary(
                p.name(),
                p.displayName(),
                p.models() != null ? p.models().stream()
                    .filter(ProviderConfiguration.ModelInfo::enabled)
                    .count() : 0
            ))
            .collect(Collectors.toList());

        return new ProvidersResponse(providerSummaries, null);
    }

    /**
     * Get models for a specific provider.
     * 
     * @param providerName the provider name (e.g., "openai", "anthropic")
     * @return list of available models for the provider
     */
    @GetMapping("/{provider}/models")
    public ModelsResponse getModels(@PathVariable String provider) {
        ProviderConfiguration.ProviderInfo providerConfig = providerConfiguration.getProviderConfig(provider);
        
        if (providerConfig == null) {
            return new ModelsResponse(List.of(), "Provider not found or not available");
        }

        List<ModelSummary> models = providerConfiguration.getModelsForProvider(provider).stream()
            .map(m -> new ModelSummary(m.name(), m.displayName()))
            .collect(Collectors.toList());

        return new ModelsResponse(models, null);
    }

    // Response DTOs

    public record ProvidersResponse(
        List<ProviderSummary> providers,
        String error
    ) {}

    public record ProviderSummary(
        String name,
        String displayName,
        long modelCount
    ) {}

    public record ModelsResponse(
        List<ModelSummary> models,
        String error
    ) {}

    public record ModelSummary(
        String name,
        String displayName
    ) {}
}
