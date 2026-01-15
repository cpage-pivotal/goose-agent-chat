package org.tanzu.goosechat;

import org.tanzu.goose.cf.spring.GenaiModelConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/diagnostics")
@CrossOrigin(origins = "*")
public class DiagnosticsController {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticsController.class);
    
    private final GenaiModelConfiguration genaiModelConfiguration;
    private final RestClient.Builder restClientBuilder;
    
    public DiagnosticsController(
            @Autowired(required = false) GenaiModelConfiguration genaiModelConfiguration,
            RestClient.Builder restClientBuilder) {
        this.genaiModelConfiguration = genaiModelConfiguration;
        this.restClientBuilder = restClientBuilder;
    }

    private static final List<String> RELEVANT_PREFIXES = List.of(
        "GOOSE", "ANTHROPIC", "OPENAI", "GOOGLE", "DATABRICKS", "OLLAMA"
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
     */
    @GetMapping("/genai-test")
    public Map<String, Object> testGenaiProxy() {
        Map<String, Object> result = new TreeMap<>();
        
        if (genaiModelConfiguration == null) {
            result.put("error", "GenaiModelConfiguration not available");
            return result;
        }
        
        Optional<GenaiModelConfiguration.ModelInfo> modelInfo = genaiModelConfiguration.getModelInfo();
        if (modelInfo.isEmpty()) {
            result.put("error", "No GenAI model configured");
            result.put("genaiAvailable", genaiModelConfiguration.isGenaiAvailable());
            return result;
        }
        
        var info = modelInfo.get();
        result.put("model", info.model());
        result.put("baseUrl", info.baseUrl());
        result.put("apiKeyLength", info.apiKey() != null ? info.apiKey().length() : 0);
        result.put("apiKeyPrefix", info.apiKey() != null && info.apiKey().length() > 10 
                ? info.apiKey().substring(0, 10) + "..." : "null");
        
        // Try to make a simple request to the GenAI endpoint
        try {
            String testUrl = info.baseUrl() + "/v1/chat/completions";
            result.put("testUrl", testUrl);
            
            String requestBody = """
                {
                    "model": "%s",
                    "messages": [{"role": "user", "content": "Say hello"}],
                    "max_tokens": 50,
                    "stream": false
                }
                """.formatted(info.model());
            
            logger.info("Testing GenAI endpoint: {} with model: {}", testUrl, info.model());
            
            RestClient client = restClientBuilder.build();
            String response = client.post()
                    .uri(testUrl)
                    .header("Authorization", "Bearer " + info.apiKey())
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
     * Test the GenAI proxy with streaming to verify SSE format.
     */
    @GetMapping("/genai-stream-test")
    public Map<String, Object> testGenaiStreamingProxy() {
        Map<String, Object> result = new TreeMap<>();
        
        if (genaiModelConfiguration == null) {
            result.put("error", "GenaiModelConfiguration not available");
            return result;
        }
        
        Optional<GenaiModelConfiguration.ModelInfo> modelInfo = genaiModelConfiguration.getModelInfo();
        if (modelInfo.isEmpty()) {
            result.put("error", "No GenAI model configured");
            return result;
        }
        
        var info = modelInfo.get();
        result.put("model", info.model());
        result.put("baseUrl", info.baseUrl());
        
        try {
            String testUrl = info.baseUrl() + "/v1/chat/completions";
            result.put("testUrl", testUrl);
            
            String requestBody = """
                {
                    "model": "%s",
                    "messages": [{"role": "user", "content": "Say hello in one word."}],
                    "max_tokens": 10,
                    "stream": true
                }
                """.formatted(info.model());
            
            logger.info("Testing GenAI STREAMING endpoint: {} with model: {}", testUrl, info.model());
            
            RestClient client = restClientBuilder.build();
            String response = client.post()
                    .uri(testUrl)
                    .header("Authorization", "Bearer " + info.apiKey())
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

