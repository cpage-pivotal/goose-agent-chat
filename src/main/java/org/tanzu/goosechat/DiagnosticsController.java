package org.tanzu.goosechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/diagnostics")
@CrossOrigin(origins = "*")
public class DiagnosticsController {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticsController.class);
    
    private final RestClient.Builder restClientBuilder;
    
    public DiagnosticsController(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    private static final List<String> RELEVANT_PREFIXES = List.of(
        "GOOSE", "ANTHROPIC", "OPENAI", "GOOGLE", "DATABRICKS", "OLLAMA", "GENAI"
    );
    private static final List<String> EXACT_MATCHES = List.of("PATH", "HOME");
    private static final List<String> SENSITIVE_PATTERNS = List.of("API_KEY", "TOKEN");

    @GetMapping("/env")
    public Map<String, String> getEnvironment() {
        Map<String, String> filtered = new TreeMap<>();

        System.getenv().forEach((key, value) -> {
            if (isRelevantEnvVar(key)) {
                filtered.put(key, maskIfSensitive(key, value));
            }
        });

        return filtered;
    }

    private boolean isRelevantEnvVar(String key) {
        return EXACT_MATCHES.contains(key) ||
               RELEVANT_PREFIXES.stream().anyMatch(key::contains);
    }

    private String maskIfSensitive(String key, String value) {
        boolean isSensitive = SENSITIVE_PATTERNS.stream().anyMatch(key::contains);
        if (isSensitive && value != null && value.length() > 10) {
            return value.substring(0, 10) + "..." + value.substring(value.length() - 4);
        }
        return value;
    }
    
    /**
     * Test the GenAI proxy directly and return raw response info for debugging.
     * This bypasses Goose CLI to verify the GenAI endpoint is working.
     * 
     * GenAI configuration is now handled via environment variables set by the buildpack:
     * - OPENAI_HOST: The GenAI-compatible endpoint URL
     * - OPENAI_API_KEY: The API key for authentication
     * - GOOSE_MODEL: The model name
     * - GENAI_SERVICE_NAME: The name of the bound GenAI service
     */
    @GetMapping("/genai-test")
    public Map<String, Object> testGenaiProxy() {
        Map<String, Object> result = new TreeMap<>();
        
        String openaiHost = System.getenv("OPENAI_HOST");
        String openaiApiKey = System.getenv("OPENAI_API_KEY");
        String model = System.getenv("GOOSE_MODEL");
        String genaiServiceName = System.getenv("GENAI_SERVICE_NAME");
        
        if (openaiHost == null || openaiHost.isEmpty()) {
            result.put("error", "OPENAI_HOST not configured");
            result.put("genaiServiceName", genaiServiceName);
            return result;
        }
        
        if (openaiApiKey == null || openaiApiKey.isEmpty()) {
            result.put("error", "OPENAI_API_KEY not configured");
            return result;
        }
        
        if (model == null || model.isEmpty()) {
            model = "default";
        }
        
        result.put("model", model);
        result.put("baseUrl", openaiHost);
        result.put("genaiServiceName", genaiServiceName);
        result.put("apiKeyLength", openaiApiKey.length());
        result.put("apiKeyPrefix", openaiApiKey.length() > 10 
                ? openaiApiKey.substring(0, 10) + "..." : "***");
        
        // Try to make a simple request to the GenAI endpoint
        try {
            String testUrl = openaiHost + "/v1/chat/completions";
            result.put("testUrl", testUrl);
            
            String requestBody = """
                {
                    "model": "%s",
                    "messages": [{"role": "user", "content": "Say hello"}],
                    "max_tokens": 50,
                    "stream": false
                }
                """.formatted(model);
            
            logger.info("Testing GenAI endpoint: {} with model: {}", testUrl, model);
            
            RestClient client = restClientBuilder.build();
            String response = client.post()
                    .uri(testUrl)
                    .header("Authorization", "Bearer " + openaiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            
            result.put("success", true);
            result.put("response", response);
            logger.info("GenAI test response: {}", response);
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            logger.error("GenAI test failed", e);
        }
        
        return result;
    }
    
    /**
     * Test Goose CLI directly with a simple prompt (no streaming JSON).
     * This helps diagnose if the issue is with Goose or the GenAI proxy.
     */
    @GetMapping("/goose-test")
    public Map<String, Object> testGooseCli() {
        Map<String, Object> result = new TreeMap<>();
        
        String goosePath = System.getenv("GOOSE_CLI_PATH");
        String provider = System.getenv("GOOSE_PROVIDER");
        String model = System.getenv("GOOSE_MODEL");
        String openaiHost = System.getenv("OPENAI_HOST");
        
        result.put("goosePath", goosePath);
        result.put("provider", provider);
        result.put("model", model);
        result.put("openaiHost", openaiHost);
        
        if (goosePath == null || goosePath.isEmpty()) {
            result.put("error", "GOOSE_CLI_PATH not set");
            return result;
        }
        
        try {
            // Run a simple goose command without streaming JSON
            ProcessBuilder pb = new ProcessBuilder(
                goosePath, "session", "--text", "Say hello in one word.", "--max-turns", "1"
            );
            pb.environment().put("GOOSE_DEBUG", "true");
            pb.environment().put("RUST_LOG", "goose=debug");
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            process.getOutputStream().close();
            
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.info("Goose test output: {}", line);
                }
            }
            
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.put("error", "Goose command timed out after 30 seconds");
                result.put("partialOutput", output.toString());
                return result;
            }
            
            int exitCode = process.exitValue();
            result.put("exitCode", exitCode);
            result.put("output", output.toString());
            result.put("success", exitCode == 0);
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            logger.error("Goose test failed", e);
        }
        
        return result;
    }
    
    /**
     * Test the GenAI proxy with streaming to verify SSE format.
     */
    @GetMapping("/genai-stream-test")
    public Map<String, Object> testGenaiStreamingProxy() {
        Map<String, Object> result = new TreeMap<>();
        
        String openaiHost = System.getenv("OPENAI_HOST");
        String openaiApiKey = System.getenv("OPENAI_API_KEY");
        String model = System.getenv("GOOSE_MODEL");
        
        if (openaiHost == null || openaiHost.isEmpty()) {
            result.put("error", "OPENAI_HOST not configured");
            return result;
        }
        
        if (openaiApiKey == null || openaiApiKey.isEmpty()) {
            result.put("error", "OPENAI_API_KEY not configured");
            return result;
        }
        
        if (model == null || model.isEmpty()) {
            model = "default";
        }
        
        result.put("model", model);
        result.put("baseUrl", openaiHost);
        
        try {
            String testUrl = openaiHost + "/v1/chat/completions";
            result.put("testUrl", testUrl);
            
            String requestBody = """
                {
                    "model": "%s",
                    "messages": [{"role": "user", "content": "Say hello in one word."}],
                    "max_tokens": 10,
                    "stream": true
                }
                """.formatted(model);
            
            logger.info("Testing GenAI STREAMING endpoint: {} with model: {}", testUrl, model);
            
            RestClient client = restClientBuilder.build();
            String response = client.post()
                    .uri(testUrl)
                    .header("Authorization", "Bearer " + openaiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            
            result.put("success", true);
            result.put("rawResponse", response);
            // Count the number of 'data:' lines
            long dataLineCount = response.lines().filter(line -> line.startsWith("data:")).count();
            result.put("dataLineCount", dataLineCount);
            logger.info("GenAI streaming test raw response ({} lines): {}", dataLineCount, response);
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            logger.error("GenAI streaming test failed", e);
        }
        
        return result;
    }
}
