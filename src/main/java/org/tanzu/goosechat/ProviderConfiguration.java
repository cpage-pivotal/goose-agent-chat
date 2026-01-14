package org.tanzu.goosechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Component that loads and manages provider configurations from providers-config.yml.
 * <p>
 * This component reads the provider configuration file and provides methods to:
 * <ul>
 *   <li>List available providers</li>
 *   <li>Get models for a specific provider</li>
 *   <li>Validate provider/model combinations</li>
 *   <li>Resolve API keys from environment variables</li>
 * </ul>
 * </p>
 */
@Component
public class ProviderConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ProviderConfiguration.class);
    private static final String CONFIG_FILE = "providers-config.yml";

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private ProvidersConfig config;
    private final Map<String, ProviderInfo> providerMap = new HashMap<>();

    @PostConstruct
    public void loadConfiguration() {
        try {
            ClassPathResource resource = new ClassPathResource(CONFIG_FILE);
            if (!resource.exists()) {
                logger.warn("Provider configuration file {} not found, using defaults", CONFIG_FILE);
                config = new ProvidersConfig(new ArrayList<>());
                return;
            }

            try (InputStream inputStream = resource.getInputStream()) {
                config = yamlMapper.readValue(inputStream, ProvidersConfig.class);
                logger.info("Loaded {} providers from {}", 
                    config.providers() != null ? config.providers().size() : 0, CONFIG_FILE);

                // Build provider map for quick lookup
                if (config.providers() != null) {
                    for (ProviderInfo provider : config.providers()) {
                        providerMap.put(provider.name(), provider);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load provider configuration from {}", CONFIG_FILE, e);
            config = new ProvidersConfig(new ArrayList<>());
        }
    }

    /**
     * Get all available (enabled) providers.
     * 
     * @return list of enabled providers with API keys available
     */
    public List<ProviderInfo> getAvailableProviders() {
        if (config == null || config.providers() == null) {
            return Collections.emptyList();
        }

        return config.providers().stream()
            .filter(ProviderInfo::enabled)
            .filter(this::hasApiKey)
            .collect(Collectors.toList());
    }

    /**
     * Get models for a specific provider.
     * 
     * @param providerName the provider name (e.g., "openai", "anthropic")
     * @return list of enabled models for the provider
     */
    public List<ModelInfo> getModelsForProvider(String providerName) {
        ProviderInfo provider = providerMap.get(providerName);
        if (provider == null || !provider.enabled()) {
            return Collections.emptyList();
        }

        if (provider.models() == null) {
            return Collections.emptyList();
        }

        return provider.models().stream()
            .filter(ModelInfo::enabled)
            .collect(Collectors.toList());
    }

    /**
     * Get full configuration for a provider.
     * 
     * @param providerName the provider name
     * @return provider configuration or null if not found/disabled
     */
    public ProviderInfo getProviderConfig(String providerName) {
        ProviderInfo provider = providerMap.get(providerName);
        if (provider == null || !provider.enabled() || !hasApiKey(provider)) {
            return null;
        }
        return provider;
    }

    /**
     * Validate that a provider/model combination is valid and enabled.
     * 
     * @param providerName the provider name
     * @param modelName the model name
     * @return true if the combination is valid
     */
    public boolean validateProviderModel(String providerName, String modelName) {
        if (providerName == null || modelName == null) {
            return false;
        }

        ProviderInfo provider = getProviderConfig(providerName);
        if (provider == null) {
            return false;
        }

        return provider.models() != null && provider.models().stream()
            .anyMatch(m -> m.enabled() && m.name().equals(modelName));
    }

    /**
     * Get the API key for a provider from environment variables.
     * 
     * @param providerName the provider name
     * @return the API key or null if not found
     */
    public String getApiKey(String providerName) {
        ProviderInfo provider = providerMap.get(providerName);
        if (provider == null || provider.apiKeyEnv() == null) {
            return null;
        }

        String apiKey = System.getenv(provider.apiKeyEnv());
        if (apiKey == null || apiKey.isEmpty()) {
            logger.debug("API key environment variable {} not set for provider {}", 
                provider.apiKeyEnv(), providerName);
            return null;
        }

        return apiKey;
    }

    /**
     * Get the base URL for a provider.
     * 
     * @param providerName the provider name
     * @return the base URL or null if using default
     */
    public String getBaseUrl(String providerName) {
        ProviderInfo provider = providerMap.get(providerName);
        if (provider == null) {
            return null;
        }

        // Check baseUrlEnv first (for providers like Databricks)
        if (provider.baseUrlEnv() != null) {
            String baseUrl = System.getenv(provider.baseUrlEnv());
            if (baseUrl != null && !baseUrl.isEmpty()) {
                return baseUrl;
            }
        }

        // Check baseUrl field (for custom URLs)
        if (provider.baseUrl() != null && !provider.baseUrl().isEmpty()) {
            return provider.baseUrl();
        }

        // Return null to use default provider URL
        return null;
    }

    /**
     * Check if a provider has an API key available.
     */
    private boolean hasApiKey(ProviderInfo provider) {
        // For Ollama, API key is not required
        if ("ollama".equals(provider.name())) {
            if (provider.baseUrlEnv() != null) {
                String baseUrl = System.getenv(provider.baseUrlEnv());
                return baseUrl != null && !baseUrl.isEmpty();
            }
            return true; // Ollama can work without explicit host
        }

        // For other providers, check API key
        if (provider.apiKeyEnv() == null) {
            return false;
        }

        String apiKey = System.getenv(provider.apiKeyEnv());
        return apiKey != null && !apiKey.isEmpty();
    }

    // Configuration data classes

    public record ProvidersConfig(List<ProviderInfo> providers) {}

    public record ProviderInfo(
        String name,
        String displayName,
        boolean enabled,
        String apiKeyEnv,
        String baseUrl,
        String baseUrlEnv,
        List<ModelInfo> models
    ) {}

    public record ModelInfo(
        String name,
        String displayName,
        boolean enabled
    ) {}
}
