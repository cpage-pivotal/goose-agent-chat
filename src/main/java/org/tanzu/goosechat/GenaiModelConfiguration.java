package org.tanzu.goosechat;

import io.pivotal.cfenv.boot.genai.GenaiLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Configuration component that discovers and caches GenAI model information.
 * <p>
 * When the application is bound to a Cloud Foundry GenAI service, the {@link GenaiLocator}
 * bean is auto-configured by java-cfenv. This component uses the locator to discover
 * available TOOLS-capable models that can be used with Goose.
 * </p>
 * <p>
 * <strong>Lazy Initialization:</strong> The actual model discovery is performed lazily
 * on the first call to {@link #getModelInfo()}, not during bean construction. This ensures
 * that the Spring context is fully initialized and the GenaiLocator is ready to make
 * HTTP calls to the config endpoint.
 * </p>
 *
 * @see io.pivotal.cfenv.boot.genai.GenaiLocator
 */
@Component
public class GenaiModelConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(GenaiModelConfiguration.class);

    private final GenaiLocator locator;
    private final String apiKey;
    private final String apiBase;

    private volatile ModelInfo cachedModelInfo;
    private volatile boolean initialized = false;

    /**
     * Creates a new GenaiModelConfiguration.
     *
     * @param locator the GenaiLocator bean (may be null if no GenAI service is bound)
     * @param apiKey the API key from genai.locator.api-key property (may be null)
     * @param apiBase the API base URL from genai.locator.api-base property (may be null)
     */
    public GenaiModelConfiguration(
            @Autowired(required = false) GenaiLocator locator,
            @Value("${genai.locator.api-key:}") String apiKey,
            @Value("${genai.locator.api-base:}") String apiBase) {
        this.locator = locator;
        this.apiKey = apiKey;
        this.apiBase = apiBase;

        if (locator != null) {
            logger.info("GenaiLocator is available - will discover models lazily on first request");
        } else {
            logger.info("GenaiLocator not available - using environment-configured model");
        }
    }

    /**
     * Check if a GenAI service is potentially available.
     * <p>
     * This is a quick check that doesn't trigger model discovery.
     * </p>
     *
     * @return true if a GenaiLocator is configured
     */
    public boolean isGenaiAvailable() {
        return locator != null;
    }

    /**
     * Lazily fetches and caches the GenAI model configuration.
     * <p>
     * Called on first request, not during bean initialization. The result is cached
     * to avoid repeated HTTP calls to the GenAI config endpoint.
     * </p>
     *
     * @return the discovered model info, or empty if no suitable model is available
     */
    public Optional<ModelInfo> getModelInfo() {
        // Check for bypass flag
        boolean bypassGenai = Boolean.parseBoolean(System.getenv().getOrDefault("BYPASS_GENAI", "false"));
        if (bypassGenai) {
            logger.info("BYPASS_GENAI is set - skipping GenAI model discovery");
            return Optional.empty();
        }
        
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    cachedModelInfo = discoverModel();
                    initialized = true;
                }
            }
        }
        return Optional.ofNullable(cachedModelInfo);
    }

    /**
     * Discovers a suitable model from the GenAI service.
     * <p>
     * Looks for models with TOOLS capability, which is required for Goose agent functionality.
     * </p>
     */
    private ModelInfo discoverModel() {
        if (locator == null) {
            logger.debug("No GenaiLocator available, skipping model discovery");
            return null;
        }

        try {
            logger.info("Discovering GenAI models...");
            logger.info("GenAI locator apiKey present: {}, apiBase present: {}",
                    apiKey != null && !apiKey.isEmpty(),
                    apiBase != null && !apiBase.isEmpty());
            
            // #region agent log
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                    (java.time.Instant.now().toString() + "|GenaiModelConfiguration.java:121|discoverModel|DISCOVERY_START|hypothesisId:F|data:{\"apiBase\":\"" + (apiBase != null ? apiBase.replace("\"", "'") : "null") + "\",\"apiKeyPresent\":" + (apiKey != null && !apiKey.isEmpty()) + "}\n").getBytes(), 
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {}
            // #endregion

            // Get TOOLS-capable models (required for Goose agent functionality)
            List<String> toolModels = locator.getModelNamesByCapability("TOOLS");

            if (toolModels.isEmpty()) {
                logger.warn("No TOOLS-capable models found in GenAI service");
                return null;
            }

            String modelName = toolModels.get(0);
            logger.info("Discovered GenAI model: {} (from {} available TOOLS-capable models)",
                    modelName, toolModels.size());

            // Build the OpenAI-compatible base URL
            // The apiBase from java-cfenv may include the model name in the path
            // We need to extract just the base URL without the model path
            String openaiBaseUrl = apiBase;
            
            // #region agent log
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                    (java.time.Instant.now().toString() + "|GenaiModelConfiguration.java:143|discoverModel|BEFORE_URL_CONSTRUCTION|hypothesisId:F|data:{\"apiBase\":\"" + (apiBase != null ? apiBase.replace("\"", "'") : "null") + "\",\"modelName\":\"" + modelName + "\"}\n").getBytes(), 
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {}
            // #endregion
            
            if (openaiBaseUrl != null && !openaiBaseUrl.isEmpty()) {
                // Remove model name from path if present (e.g., /OpenAI-GPT5-2-81a4d41)
                // The apiBase might be like: https://genai-proxy.../OpenAI-GPT5-2-81a4d41
                // We need: https://genai-proxy.../
                try {
                    java.net.URI uri = java.net.URI.create(openaiBaseUrl);
                    String path = uri.getPath();
                    
                    // #region agent log
                    try {
                        java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                            (java.time.Instant.now().toString() + "|GenaiModelConfiguration.java:152|discoverModel|URL_PARSED|hypothesisId:F|data:{\"originalPath\":\"" + (path != null ? path : "null") + "\",\"originalUrl\":\"" + openaiBaseUrl.replace("\"", "'") + "\"}\n").getBytes(), 
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    } catch (Exception e) {}
                    // #endregion
                    
                    // If path contains model name pattern (e.g., /OpenAI-* or contains model name), remove it
                    if (path != null && !path.isEmpty() && !path.equals("/")) {
                        // Check if path ends with something that looks like a model identifier
                        // Model names often contain dashes and alphanumeric characters
                        String[] pathParts = path.split("/");
                        if (pathParts.length > 0) {
                            String lastPart = pathParts[pathParts.length - 1];
                            // If last part looks like a model name (contains dashes, alphanumeric, matches model name pattern)
                            // Check if it matches common patterns: OpenAI-*, GPT*, or contains the discovered model name
                            boolean looksLikeModelName = (lastPart.contains("-") && 
                                (lastPart.contains("GPT") || lastPart.contains("OpenAI") || 
                                 lastPart.matches(".*[A-Z0-9-]+.*") || 
                                 modelName != null && (lastPart.contains(modelName) || modelName.contains(lastPart))));
                            
                            if (looksLikeModelName) {
                                // Reconstruct URL without the model name
                                StringBuilder newPath = new StringBuilder();
                                for (int i = 0; i < pathParts.length - 1; i++) {
                                    if (!pathParts[i].isEmpty()) {
                                        newPath.append("/").append(pathParts[i]);
                                    }
                                }
                                if (newPath.length() == 0) {
                                    newPath.append("/");
                                }
                                openaiBaseUrl = uri.getScheme() + "://" + uri.getAuthority() + newPath.toString();
                                
                                // #region agent log
                                try {
                                    java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                                        (java.time.Instant.now().toString() + "|GenaiModelConfiguration.java:175|discoverModel|MODEL_NAME_REMOVED|hypothesisId:F|data:{\"removedPart\":\"" + lastPart + "\",\"newBaseUrl\":\"" + openaiBaseUrl.replace("\"", "'") + "\"}\n").getBytes(), 
                                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                                } catch (Exception e) {}
                                // #endregion
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse apiBase URL: {}", openaiBaseUrl, e);
                    // #region agent log
                    try {
                        java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                            (java.time.Instant.now().toString() + "|GenaiModelConfiguration.java:182|discoverModel|URL_PARSE_ERROR|hypothesisId:F|data:{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}\n").getBytes(), 
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    } catch (Exception ex) {}
                    // #endregion
                }
                
                // Append /openai for OpenAI wire format
                // Check if URL already contains /openai to avoid double appending
                if (!openaiBaseUrl.contains("/openai")) {
                    if (!openaiBaseUrl.endsWith("/")) {
                        openaiBaseUrl = openaiBaseUrl + "/";
                    }
                    openaiBaseUrl = openaiBaseUrl + "openai";
                }
            }
            
            // #region agent log
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                    (java.time.Instant.now().toString() + "|GenaiModelConfiguration.java:181|discoverModel|AFTER_URL_CONSTRUCTION|hypothesisId:F|data:{\"openaiBaseUrl\":\"" + (openaiBaseUrl != null ? openaiBaseUrl.replace("\"", "'") : "null") + "\"}\n").getBytes(), 
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {}
            // #endregion

            ModelInfo modelInfo = new ModelInfo(modelName, apiKey, openaiBaseUrl);
            logger.info("GenAI model configuration: model={}, baseUrl={}, apiKeyLength={}",
                    modelInfo.model(), modelInfo.baseUrl(), 
                    modelInfo.apiKey() != null ? modelInfo.apiKey().length() : 0);
            
            // #region agent log
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                    (java.time.Instant.now().toString() + "|GenaiModelConfiguration.java:187|discoverModel|MODEL_INFO_CREATED|hypothesisId:F|data:{\"model\":\"" + modelInfo.model() + "\",\"baseUrl\":\"" + (modelInfo.baseUrl() != null ? modelInfo.baseUrl().replace("\"", "'") : "null") + "\"}\n").getBytes(), 
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {}
            // #endregion

            return modelInfo;

        } catch (Exception e) {
            logger.warn("Failed to discover GenAI model: {}", e.getMessage());
            logger.debug("GenAI model discovery error details", e);
            return null;
        }
    }

    /**
     * Information about a discovered GenAI model.
     *
     * @param model the model name (e.g., "gpt-4")
     * @param apiKey the API key for authentication
     * @param baseUrl the OpenAI-compatible base URL
     */
    public record ModelInfo(String model, String apiKey, String baseUrl) {}
}
