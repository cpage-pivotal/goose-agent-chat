package org.tanzu.goosechat;

import org.tanzu.goose.cf.spring.GenaiModelConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tanzu.goose.cf.GooseExecutor;

import java.util.Optional;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatHealthController {

    private final Optional<GooseExecutor> executor;
    private final GenaiModelConfiguration genaiModelConfiguration;

    public ChatHealthController(
            @Autowired(required = false) GooseExecutor executor,
            GenaiModelConfiguration genaiModelConfiguration) {
        this.executor = Optional.ofNullable(executor);
        this.genaiModelConfiguration = genaiModelConfiguration;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        if (executor.isEmpty()) {
            return new HealthResponse(
                false, 
                "not configured", 
                getConfiguredProvider(),
                getConfiguredModel(),
                getModelSource(),
                "Goose CLI is not configured. Please ensure GOOSE_CLI_PATH and an LLM provider API key are set."
            );
        }
        
        GooseExecutor exec = executor.get();
        boolean available = exec.isAvailable();
        String version = available ? exec.getVersion() : "unavailable";
        String message = available ? "Goose CLI is ready" : "Goose CLI binary not found or not configured";
        
        return new HealthResponse(
            available, 
            version, 
            getConfiguredProvider(),
            getConfiguredModel(),
            getModelSource(),
            message
        );
    }

    /**
     * Get the configured provider, checking GenAI first.
     */
    private String getConfiguredProvider() {
        // Check GenAI configuration first (takes precedence)
        var genaiModel = genaiModelConfiguration.getModelInfo();
        if (genaiModel.isPresent()) {
            return "openai";  // GenAI provides OpenAI-compatible API
        }
        
        // Fall back to environment configuration
        return getEnvOrElse("GOOSE_PROVIDER__TYPE",
               getEnvOrElse("GOOSE_PROVIDER",
               inferProviderFromApiKeys()));
    }

    /**
     * Get the configured model, checking GenAI first.
     */
    private String getConfiguredModel() {
        // Check GenAI configuration first (takes precedence)
        var genaiModel = genaiModelConfiguration.getModelInfo();
        if (genaiModel.isPresent()) {
            return genaiModel.get().model();
        }
        
        // Fall back to environment configuration
        return getEnvOrElse("GOOSE_PROVIDER__MODEL",
               getEnvOrElse("GOOSE_MODEL", "default"));
    }

    /**
     * Get the source of the model configuration.
     * 
     * @return "genai-service" if using GenAI, "environment" otherwise
     */
    private String getModelSource() {
        var genaiModel = genaiModelConfiguration.getModelInfo();
        return genaiModel.isPresent() ? "genai-service" : "environment";
    }

    private String inferProviderFromApiKeys() {
        if (isEnvSet("ANTHROPIC_API_KEY")) return "anthropic";
        if (isEnvSet("OPENAI_API_KEY")) return "openai";
        if (isEnvSet("GOOGLE_API_KEY")) return "google";
        if (isEnvSet("DATABRICKS_HOST")) return "databricks";
        if (isEnvSet("OLLAMA_HOST")) return "ollama";
        return "unknown";
    }

    private String getEnvOrElse(String name, String fallback) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : fallback;
    }

    private boolean isEnvSet(String name) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty();
    }

    /**
     * Health response including model source information.
     * 
     * @param available whether Goose CLI is available
     * @param version the Goose CLI version
     * @param provider the LLM provider (e.g., "openai", "anthropic")
     * @param model the model name
     * @param source the source of model config ("genai-service" or "environment")
     * @param message descriptive message
     */
    public record HealthResponse(
        boolean available, 
        String version, 
        String provider,
        String model,
        String source,
        String message
    ) {}
}

