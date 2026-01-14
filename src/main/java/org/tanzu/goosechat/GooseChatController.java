package org.tanzu.goosechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.tanzu.goose.cf.GooseExecutor;
import org.tanzu.goose.cf.GooseExecutionException;
import org.tanzu.goose.cf.GooseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * REST controller for managing conversational chat sessions with Goose CLI.
 * <p>
 * This controller provides endpoints for:
 * <ul>
 *   <li>Creating new conversation sessions</li>
 *   <li>Sending messages to existing sessions (with streaming responses)</li>
 *   <li>Closing conversation sessions</li>
 *   <li>Checking session status</li>
 * </ul>
 * </p>
 * 
 * <h3>Conversation Sessions</h3>
 * <p>
 * Each chat session maintains conversation context across multiple messages.
 * Sessions use Goose's native named session feature with {@code --name} and 
 * {@code --resume} flags to enable multi-turn conversations where Goose 
 * remembers previous exchanges. Session data is stored in Goose's SQLite 
 * database at {@code ~/.local/share/goose/sessions/sessions.db}.
 * </p>
 * 
 * @see <a href="https://block.github.io/goose/docs/guides/sessions/">Goose Session Management</a>
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = "goose.enabled", havingValue = "true", matchIfMissing = true)
public class GooseChatController {

    private static final Logger logger = LoggerFactory.getLogger(GooseChatController.class);
    private static final String SESSION_PREFIX = "chat-";
    
    private final GooseExecutor executor;
    private final GenaiModelConfiguration genaiModelConfiguration;
    private final ProviderConfiguration providerConfiguration;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    
    // Session metadata tracking (Goose handles actual session persistence)
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-cleanup");
        t.setDaemon(true);
        return t;
    });

    public GooseChatController(
            GooseExecutor executor, 
            GenaiModelConfiguration genaiModelConfiguration,
            ProviderConfiguration providerConfiguration) {
        this.executor = executor;
        this.genaiModelConfiguration = genaiModelConfiguration;
        this.providerConfiguration = providerConfiguration;
        logger.info("GooseChatController initialized with Goose native session support");
        
        // Schedule periodic session cleanup
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSessions, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Create a new conversation session.
     * 
     * @param request optional session configuration
     * @return session ID and success status
     */
    @PostMapping("/sessions")
    public ResponseEntity<CreateSessionResponse> createSession(
            @RequestBody(required = false) CreateSessionRequest request) {
        logger.info("Creating new conversation session");
        
            // #region agent log
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                    (java.time.Instant.now().toString() + "|GooseChatController.java:88|createSession|ENTRY|hypothesisId:A|data:{\"request\":\"" + (request != null ? request.toString().replace("\"", "'") : "null") + "\"}\n").getBytes(), 
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {}
            // #endregion
        
        try {
            if (!executor.isAvailable()) {
                logger.error("Goose CLI is not available");
                // #region agent log
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/goose-agent-chat/.cursor/debug.log"), 
                        (java.time.Instant.now().toString() + "|GooseChatController.java:92|createSession|GOOSE_NOT_AVAILABLE|hypothesisId:B|data:{\"executorAvailable\":false}\n").getBytes(), 
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception e) {}
                // #endregion
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new CreateSessionResponse(null, false, "Goose CLI is not available"));
            }

            // Generate session ID with prefix for Goose named sessions
            // This creates names like "chat-a1b2c3d4" which Goose stores in its SQLite DB
            String sessionId = SESSION_PREFIX + UUID.randomUUID().toString().substring(0, 8);
            
            // #region agent log
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                    (java.time.Instant.now().toString() + "|GooseChatController.java:100|createSession|SESSION_ID_GENERATED|hypothesisId:A|data:{\"sessionId\":\"" + sessionId + "\"}\n").getBytes(), 
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {}
            // #endregion
            
            // Apply custom configuration if provided
            long inactivityTimeoutMinutes = 30; // default
            String provider = null;
            String model = null;
            
            if (request != null) {
                if (request.sessionInactivityTimeoutMinutes() != null) {
                    inactivityTimeoutMinutes = request.sessionInactivityTimeoutMinutes();
                }
                provider = request.provider();
                model = request.model();
                
                // Validate provider/model combination if both are provided
                if (provider != null && model != null) {
                    if (!providerConfiguration.validateProviderModel(provider, model)) {
                        logger.warn("Invalid provider/model combination: {}/{}", provider, model);
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(new CreateSessionResponse(null, false, 
                                String.format("Invalid provider/model combination: %s/%s", provider, model)));
                    }
                } else if (provider != null) {
                    // Validate provider exists and is enabled
                    if (providerConfiguration.getProviderConfig(provider) == null) {
                        logger.warn("Provider not available: {}", provider);
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(new CreateSessionResponse(null, false, 
                                String.format("Provider not available: %s", provider)));
                    }
                }
            }

            ConversationSession session = new ConversationSession(
                provider,
                model,
                Duration.ofMinutes(inactivityTimeoutMinutes),
                System.currentTimeMillis()
            );
            
            sessions.put(sessionId, session);
            
            // #region agent log
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                    (java.time.Instant.now().toString() + "|GooseChatController.java:122|createSession|SESSION_STORED|hypothesisId:A|data:{\"sessionId\":\"" + sessionId + "\",\"sessionsSize\":" + sessions.size() + "}\n").getBytes(), 
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {}
            // #endregion
            
            logger.info("Created conversation session: {} with provider: {}", sessionId, provider);
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateSessionResponse(sessionId, true, null));
                
        } catch (Exception e) {
            logger.error("Failed to create conversation session", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CreateSessionResponse(null, false, 
                    "Failed to create session: " + e.getMessage()));
        }
    }

    /**
     * Send a message to an existing session and stream the response via SSE.
     * <p>
     * Uses the native browser EventSource API (GET requests only) which handles
     * proxy buffering and chunked encoding better than fetch-based SSE parsing.
     * This is critical for Cloud Foundry's Go Router.
     * </p>
     * <p>
     * Uses Goose CLI's {@code --output-format stream-json} for true token-level streaming.
     * </p>
     * <p>
     * SSE Events emitted:
     * <ul>
     *   <li>{@code status} - Initial processing status</li>
     *   <li>{@code token} - Text tokens as they arrive from the LLM</li>
     *   <li>{@code activity} - Tool calls and notifications (for activity panel)</li>
     *   <li>{@code complete} - Completion event with token count</li>
     *   <li>{@code error} - Error events</li>
     * </ul>
     * </p>
     * 
     * @param sessionId the conversation session ID
     * @param message the message to send (URL-encoded)
     * @return SSE stream of response events
     */
    @GetMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @PathVariable String sessionId,
            @RequestParam String message,
            HttpServletResponse response) {
        logger.info("Streaming message to session {}: {} chars", sessionId, message.length());
        
            // #region agent log
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                    (java.time.Instant.now().toString() + "|GooseChatController.java:161|streamMessage|ENTRY|hypothesisId:C|data:{\"sessionId\":\"" + sessionId + "\",\"messageLength\":" + message.length() + "}\n").getBytes(), 
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {}
            // #endregion
        
        // Disable buffering for SSE - critical for Cloud Foundry and reverse proxies
        // Note: Do NOT set Transfer-Encoding manually - Tomcat adds it automatically
        // and setting it twice causes "too many transfer encodings" error in Go Router
        response.setHeader("X-Accel-Buffering", "no");  // Nginx
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes timeout
        
        executorService.execute(() -> {
            Stream<String> jsonStream = null;
            try {
                if (!executor.isAvailable()) {
                    logger.error("Goose CLI is not available");
                    // #region agent log
                    try {
                        java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                            (java.time.Instant.now().toString() + "|GooseChatController.java:180|streamMessage|GOOSE_NOT_AVAILABLE|hypothesisId:B|data:{\"sessionId\":\"" + sessionId + "\"}\n").getBytes(), 
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    } catch (Exception e) {}
                    // #endregion
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Goose CLI is not available"));
                    emitter.complete();
                    return;
                }

                ConversationSession session = sessions.get(sessionId);
                    // #region agent log
                    try {
                        java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                            (java.time.Instant.now().toString() + "|GooseChatController.java:189|streamMessage|SESSION_LOOKUP|hypothesisId:A|data:{\"sessionId\":\"" + sessionId + "\",\"sessionFound\":" + (session != null) + ",\"sessionsSize\":" + sessions.size() + "}\n").getBytes(), 
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    } catch (Exception e) {}
                    // #endregion
                if (session == null || !isSessionActive(sessionId)) {
                    logger.error("Session {} is not active or does not exist", sessionId);
                    // #region agent log
                    try {
                        java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                            (java.time.Instant.now().toString() + "|GooseChatController.java:191|streamMessage|SESSION_NOT_FOUND|hypothesisId:A|data:{\"sessionId\":\"" + sessionId + "\",\"sessionNull\":" + (session == null) + "}\n").getBytes(), 
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    } catch (Exception e) {}
                    // #endregion
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Session not found or has expired"));
                    emitter.complete();
                    return;
                }

                // Update last activity
                session.updateLastActivity();

                // Send initial status event to confirm processing started
                emitter.send(SseEmitter.event()
                    .name("status")
                    .data("Processing your request..."));

                // Build options for this session
                // Priority: 1. GenAI service (if available), 2. Session config, 3. Environment
                GooseOptions options = buildGooseOptions(session);
                
                // #region agent log
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                        (java.time.Instant.now().toString() + "|GooseChatController.java:209|streamMessage|BEFORE_GOOSE_EXEC|hypothesisId:B|data:{\"sessionId\":\"" + sessionId + "\",\"options\":\"" + options.toString().replace("\"", "'") + "\"}\n").getBytes(), 
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception e) {}
                // #endregion

                // Execute Goose with streaming JSON output for token-level streaming
                boolean isFirstMessage = session.messageCount() == 0;
                jsonStream = executor.executeInSessionStreamingJson(
                    sessionId, message, !isFirstMessage, options
                );
                
                // #region agent log
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                        (java.time.Instant.now().toString() + "|GooseChatController.java:214|streamMessage|AFTER_GOOSE_EXEC|hypothesisId:B|data:{\"sessionId\":\"" + sessionId + "\",\"jsonStreamNull\":" + (jsonStream == null) + "}\n").getBytes(), 
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception e) {}
                // #endregion
                
                // Process each JSON event as it arrives
                // Batch tokens to work around proxy buffering (e.g., Cloud Foundry Go Router)
                final int[] tokenCount = {0};
                final StringBuilder tokenBatch = new StringBuilder();
                final long[] lastSendTime = {System.currentTimeMillis()};
                final int BATCH_SIZE_THRESHOLD = 10; // Send after N tokens
                final long BATCH_TIME_THRESHOLD_MS = 100; // Or after 100ms
                
                jsonStream.forEach(jsonLine -> {
                    try {
                        // #region agent log
                        try {
                            java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                                (java.time.Instant.now().toString() + "|GooseChatController.java:225|streamMessage|JSON_LINE_RECEIVED|hypothesisId:D|data:{\"sessionId\":\"" + sessionId + "\",\"jsonLineLength\":" + jsonLine.length() + "}\n").getBytes(), 
                                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        } catch (Exception e) {}
                        // #endregion
                        JsonNode event = objectMapper.readTree(jsonLine);
                        String eventType = event.has("type") ? event.get("type").asText() : "";
                        
                        // #region agent log
                        try {
                            java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                                (java.time.Instant.now().toString() + "|GooseChatController.java:228|streamMessage|JSON_PARSED|hypothesisId:D|data:{\"sessionId\":\"" + sessionId + "\",\"eventType\":\"" + eventType + "\"}\n").getBytes(), 
                                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        } catch (Exception e) {}
                        // #endregion
                        
                        switch (eventType) {
                            case "message" -> {
                                // Check for tool requests in the message content
                                String activityJson = extractToolActivityFromMessage(event, sessionId);
                                if (activityJson != null) {
                                    emitter.send(SseEmitter.event()
                                        .name("activity")
                                        .data(activityJson));
                                }
                                
                                // Also extract text tokens if present
                                String token = extractTextFromMessage(event);
                                if (token != null) {
                                    tokenBatch.append(token);
                                    tokenCount[0]++;
                                    
                                    long now = System.currentTimeMillis();
                                    boolean shouldFlush = tokenCount[0] % BATCH_SIZE_THRESHOLD == 0 
                                        || (now - lastSendTime[0]) > BATCH_TIME_THRESHOLD_MS
                                        || token.contains("\n");
                                    
                                    if (shouldFlush && !tokenBatch.isEmpty()) {
                                        // Wrap token in JSON to preserve whitespace
                                        String tokenJson = objectMapper.writeValueAsString(tokenBatch.toString());
                                        emitter.send(SseEmitter.event()
                                            .name("token")
                                            .data(tokenJson));
                                        tokenBatch.setLength(0);
                                        lastSendTime[0] = now;
                                    }
                                }
                            }
                            case "notification" -> {
                                // Forward notification events as activity
                                String activityJson = formatNotificationActivity(event, sessionId);
                                if (activityJson != null) {
                                    emitter.send(SseEmitter.event()
                                        .name("activity")
                                        .data(activityJson));
                                }
                            }
                            case "complete" -> {
                                // Flush any remaining tokens before complete
                                if (!tokenBatch.isEmpty()) {
                                    // Wrap token in JSON to preserve whitespace
                                    String tokenJson = objectMapper.writeValueAsString(tokenBatch.toString());
                                    emitter.send(SseEmitter.event()
                                        .name("token")
                                        .data(tokenJson));
                                    tokenBatch.setLength(0);
                                }
                                // Send complete event
                                processCompleteEvent(jsonLine, emitter, sessionId);
                            }
                        }
                    } catch (IOException e) {
                        logger.error("Error sending SSE event for session {}", sessionId, e);
                        throw new RuntimeException("SSE send failed", e);
                    } catch (Exception e) {
                        logger.warn("Failed to parse JSON event for session {}: {}", sessionId, jsonLine, e);
                    }
                });
                
                // Flush any remaining tokens
                if (!tokenBatch.isEmpty()) {
                    try {
                        // Wrap token in JSON to preserve whitespace
                        String tokenJson = objectMapper.writeValueAsString(tokenBatch.toString());
                        emitter.send(SseEmitter.event()
                            .name("token")
                            .data(tokenJson));
                    } catch (IOException e) {
                        logger.error("Error flushing final tokens for session {}", sessionId, e);
                    }
                }
                
                logger.info("Session {} sent {} tokens in batches", sessionId, tokenCount[0]);
                
                // Increment message count
                session.incrementMessageCount();
                
                emitter.complete();
                logger.info("Streaming message completed for session {}", sessionId);
                    
            } catch (GooseExecutionException e) {
                logger.error("Goose execution failed for session {}", sessionId, e);
                // #region agent log
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                        (java.time.Instant.now().toString() + "|GooseChatController.java:314|streamMessage|GOOSE_EXEC_EXCEPTION|hypothesisId:B|data:{\"sessionId\":\"" + sessionId + "\",\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}\n").getBytes(), 
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception ex) {}
                // #endregion
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Execution failed: " + e.getMessage()));
                } catch (IOException ex) {
                    logger.error("Failed to send error event", ex);
                }
                emitter.completeWithError(e);
            } catch (Exception e) {
                logger.error("Unexpected error during message send to session {}", sessionId, e);
                // #region agent log
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                        (java.time.Instant.now().toString() + "|GooseChatController.java:324|streamMessage|UNEXPECTED_EXCEPTION|hypothesisId:C|data:{\"sessionId\":\"" + sessionId + "\",\"error\":\"" + e.getMessage().replace("\"", "'") + "\",\"class\":\"" + e.getClass().getName() + "\"}\n").getBytes(), 
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception ex) {}
                // #endregion
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("An unexpected error occurred"));
                } catch (IOException ex) {
                    logger.error("Failed to send error event", ex);
                }
                emitter.completeWithError(e);
            } finally {
                // Ensure stream is closed to clean up subprocess
                if (jsonStream != null) {
                    jsonStream.close();
                }
            }
        });

        emitter.onTimeout(() -> {
            logger.warn("SSE connection timed out for session {}", sessionId);
            emitter.complete();
        });

        emitter.onError((e) -> {
            logger.error("SSE error for session {}", sessionId, e);
        });

        return emitter;
    }

    /**
     * Extract text content from a message event.
     */
    private String extractTextFromMessage(JsonNode event) {
        JsonNode content = event.at("/message/content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                // Look for text content items
                String contentType = item.has("type") ? item.get("type").asText() : "";
                if ("text".equals(contentType) && item.has("text")) {
                    String text = item.get("text").asText();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Extract tool request activity from a message event.
     * Tool requests appear as content items with toolRequest type.
     * 
     * JSON structure from Goose:
     * {
     *   "type": "toolRequest",
     *   "id": "tool123",
     *   "toolCall": {
     *     "status": "success",
     *     "value": { "name": "extension__tool", "arguments": {...} }
     *   }
     * }
     * 
     * @return JSON string for the activity event, or null if no tool activity
     */
    private String extractToolActivityFromMessage(JsonNode event, String sessionId) {
        JsonNode content = event.at("/message/content");
        if (!content.isArray()) {
            return null;
        }
        
        for (JsonNode item : content) {
            String contentType = item.has("type") ? item.get("type").asText() : "";
            
            // Handle tool requests (Goose uses camelCase: "toolRequest")
            if ("tool_request".equals(contentType) || "toolRequest".equals(contentType)) {
                try {
                    String id = item.has("id") ? item.get("id").asText() : UUID.randomUUID().toString();
                    JsonNode toolCall = item.has("toolCall") ? item.get("toolCall") : item.get("tool_call");
                    
                    String toolName = "unknown";
                    JsonNode arguments = objectMapper.createObjectNode();
                    
                    if (toolCall != null) {
                        // The toolCall has a nested structure: { "status": "...", "value": { "name": "...", "arguments": {...} } }
                        JsonNode value = toolCall.has("value") ? toolCall.get("value") : toolCall;
                        
                        if (value != null) {
                            toolName = value.has("name") ? value.get("name").asText() : "unknown";
                            arguments = value.has("arguments") ? value.get("arguments") : arguments;
                        }
                    }
                    
                    // Parse extension from tool name (format: extension__tool or extension/tool)
                    var parsedTool = parseToolName(toolName);
                    String extensionId = parsedTool.extensionId();
                    String shortToolName = parsedTool.toolName();
                    
                    // Build activity JSON
                    var activityNode = objectMapper.createObjectNode();
                    activityNode.put("id", id);
                    activityNode.put("type", "tool_request");
                    activityNode.put("toolName", shortToolName);
                    activityNode.put("extensionId", extensionId);
                    activityNode.put("status", "running");
                    activityNode.put("timestamp", System.currentTimeMillis());
                    activityNode.set("arguments", arguments);
                    
                    return objectMapper.writeValueAsString(activityNode);
                } catch (Exception e) {
                    logger.warn("Failed to extract tool activity for session {}", sessionId, e);
                }
            }
            
            // Handle tool responses
            if ("tool_response".equals(contentType) || "toolResponse".equals(contentType)) {
                try {
                    String id = item.has("id") ? item.get("id").asText() : "";
                    boolean isError = item.has("is_error") && item.get("is_error").asBoolean();
                    
                    var activityNode = objectMapper.createObjectNode();
                    activityNode.put("id", id);
                    activityNode.put("type", "tool_response");
                    activityNode.put("status", isError ? "error" : "completed");
                    activityNode.put("timestamp", System.currentTimeMillis());
                    
                    return objectMapper.writeValueAsString(activityNode);
                } catch (Exception e) {
                    logger.warn("Failed to extract tool response for session {}", sessionId, e);
                }
            }
        }
        return null;
    }

    /**
     * Format a notification event as an activity JSON.
     */
    private String formatNotificationActivity(JsonNode event, String sessionId) {
        try {
            String extensionId = event.has("extension_id") ? event.get("extension_id").asText() : "";
            JsonNode data = event.get("data");
            
            if (data == null) {
                return null;
            }
            
            var activityNode = objectMapper.createObjectNode();
            activityNode.put("id", UUID.randomUUID().toString());
            activityNode.put("type", "notification");
            activityNode.put("extensionId", extensionId);
            activityNode.put("timestamp", System.currentTimeMillis());
            activityNode.put("status", "info");
            
            // Extract message from notification data
            if (data.has("log") && data.get("log").has("message")) {
                activityNode.put("message", data.get("log").get("message").asText());
            } else if (data.has("message")) {
                activityNode.put("message", data.get("message").asText());
            } else if (data.has("progress")) {
                JsonNode progress = data.get("progress");
                double progressValue = progress.has("progress") ? progress.get("progress").asDouble() : 0;
                String progressMsg = progress.has("message") ? progress.get("message").asText() : "";
                activityNode.put("message", String.format("%.0f%% %s", progressValue * 100, progressMsg));
            } else {
                // Fallback: stringify the data
                activityNode.put("message", data.toString());
            }
            
            logger.debug("Session {} notification from {}: {}", sessionId, extensionId, 
                activityNode.has("message") ? activityNode.get("message").asText() : "");
            
            return objectMapper.writeValueAsString(activityNode);
        } catch (Exception e) {
            logger.warn("Failed to format notification for session {}", sessionId, e);
            return null;
        }
    }

    /**
     * Process a complete event from streaming JSON.
     */
    private void processCompleteEvent(String jsonLine, SseEmitter emitter, String sessionId) throws IOException {
        try {
            JsonNode event = objectMapper.readTree(jsonLine);
            int totalTokens = event.has("total_tokens") ? event.get("total_tokens").asInt() : 0;
            emitter.send(SseEmitter.event()
                .name("complete")
                .data(String.valueOf(totalTokens)));
            logger.info("Session {} completed streaming with {} total LLM tokens", sessionId, totalTokens);
        } catch (Exception e) {
            logger.warn("Failed to parse complete event for session {}: {}", sessionId, jsonLine, e);
        }
    }

    /**
     * Close a conversation session.
     * 
     * @param sessionId the session to close
     * @return success status
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<CloseSessionResponse> closeSession(@PathVariable String sessionId) {
        logger.info("Closing conversation session: {}", sessionId);
        
        try {
            ConversationSession removed = sessions.remove(sessionId);
            if (removed != null) {
                logger.info("Session {} closed successfully", sessionId);
            }
            return ResponseEntity.ok(
                new CloseSessionResponse(true, "Session closed successfully")
            );
        } catch (Exception e) {
            logger.error("Failed to close session {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CloseSessionResponse(false, 
                    "Failed to close session: " + e.getMessage()));
        }
    }

    /**
     * Check if a session is active.
     * 
     * @param sessionId the session to check
     * @return session status
     */
    @GetMapping("/sessions/{sessionId}/status")
    public ResponseEntity<SessionStatusResponse> getSessionStatus(@PathVariable String sessionId) {
        logger.debug("Checking status for session: {}", sessionId);
        
        try {
            boolean active = isSessionActive(sessionId);
            return ResponseEntity.ok(new SessionStatusResponse(sessionId, active));
        } catch (Exception e) {
            logger.error("Failed to check session status for {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new SessionStatusResponse(sessionId, false));
        }
    }

    /**
     * Check if a session is active (exists and hasn't timed out).
     */
    private boolean isSessionActive(String sessionId) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        
        long timeSinceLastActivity = System.currentTimeMillis() - session.lastActivity();
        return timeSinceLastActivity < session.inactivityTimeout().toMillis();
    }

    /**
     * Build GooseOptions for a session, using GenAI configuration if available.
     * <p>
     * Priority:
     * <ol>
     *   <li>GenAI service (if bound and has TOOLS-capable model)</li>
     *   <li>Session-specified provider/model (from ProviderConfiguration)</li>
     *   <li>Environment variables (GOOSE_PROVIDER, GOOSE_MODEL)</li>
     *   <li>Default from `.goose-config.yml`</li>
     * </ol>
     * </p>
     */
    private GooseOptions buildGooseOptions(ConversationSession session) {
        GooseOptions.Builder optionsBuilder = GooseOptions.builder()
            .timeout(Duration.ofMinutes(10));

        // Check for GenAI model first (takes precedence)
        var genaiModel = genaiModelConfiguration.getModelInfo();
        if (genaiModel.isPresent()) {
            var modelInfo = genaiModel.get();
            logger.debug("Using GenAI model: {} from {}", modelInfo.model(), modelInfo.baseUrl());
            
            // #region agent log
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("/Users/orenpenso/git/tanzu-agent/.cursor/debug.log"), 
                    (java.time.Instant.now().toString() + "|GooseChatController.java:597|buildGooseOptions|USING_GENAI|hypothesisId:F|data:{\"model\":\"" + modelInfo.model() + "\",\"baseUrl\":\"" + (modelInfo.baseUrl() != null ? modelInfo.baseUrl().replace("\"", "'") : "null") + "\",\"apiKeyPresent\":" + (modelInfo.apiKey() != null && !modelInfo.apiKey().isEmpty()) + "}\n").getBytes(), 
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {}
            // #endregion
            
            optionsBuilder
                .provider("openai")  // GenAI provides OpenAI-compatible API
                .model(modelInfo.model())
                .apiKey(modelInfo.apiKey())
                .baseUrl(modelInfo.baseUrl());
        } else if (session.provider() != null && !session.provider().isEmpty()) {
            // Use session-specified provider from ProviderConfiguration
            ProviderConfiguration.ProviderInfo providerConfig = providerConfiguration.getProviderConfig(session.provider());
            if (providerConfig != null) {
                String apiKey = providerConfiguration.getApiKey(session.provider());
                String baseUrl = providerConfiguration.getBaseUrl(session.provider());
                
                logger.debug("Using configured provider: {} with model: {}", session.provider(), session.model());
                
                optionsBuilder.provider(session.provider());
                if (session.model() != null && !session.model().isEmpty()) {
                    optionsBuilder.model(session.model());
                }
                if (apiKey != null) {
                    optionsBuilder.apiKey(apiKey);
                }
                if (baseUrl != null) {
                    optionsBuilder.baseUrl(baseUrl);
                }
            } else {
                // Fall back to environment variables or defaults
                logger.debug("Provider {} not found in configuration, using environment/defaults", session.provider());
                optionsBuilder.provider(session.provider());
                if (session.model() != null && !session.model().isEmpty()) {
                    optionsBuilder.model(session.model());
                }
            }
        } else {
            // No session provider specified - use environment or defaults
            // This maintains backward compatibility
            logger.debug("No provider specified in session, using environment/defaults");
        }

        return optionsBuilder.build();
    }

    /**
     * Clean up expired sessions.
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            ConversationSession session = entry.getValue();
            boolean expired = (now - session.lastActivity()) >= session.inactivityTimeout().toMillis();
            if (expired) {
                logger.info("Cleaning up expired session: {}", entry.getKey());
            }
            return expired;
        });
    }

    // Request/Response records
    
    public record CreateSessionRequest(
        String provider,      // anthropic, openai, google, databricks, ollama
        String model,
        Integer sessionInactivityTimeoutMinutes
    ) {}

    public record CreateSessionResponse(
        String sessionId,
        boolean success,
        String message
    ) {}

    public record CloseSessionResponse(
        boolean success,
        String message
    ) {}

    public record SessionStatusResponse(
        String sessionId,
        boolean active
    ) {}

    /**
     * Parsed tool name containing the extension ID and tool name components.
     */
    private record ParsedToolName(String extensionId, String toolName) {}

    /**
     * Parse a tool name into extension ID and short tool name.
     * Handles formats: "extension__tool" or "extension/tool"
     */
    private ParsedToolName parseToolName(String fullToolName) {
        String delimiter = fullToolName.contains("__") ? "__" :
                          fullToolName.contains("/") ? "/" : null;

        if (delimiter == null) {
            return new ParsedToolName("", fullToolName);
        }

        String[] parts = fullToolName.split(delimiter, 2);
        return new ParsedToolName(parts[0], parts.length > 1 ? parts[1] : fullToolName);
    }

    /**
     * Internal session tracking class.
     */
    private static class ConversationSession {
        private final String provider;
        private final String model;
        private final Duration inactivityTimeout;
        private volatile long lastActivity;
        private volatile int messageCount;

        ConversationSession(String provider, String model, 
                           Duration inactivityTimeout, long createdAt) {
            this.provider = provider;
            this.model = model;
            this.inactivityTimeout = inactivityTimeout;
            this.lastActivity = createdAt;
            this.messageCount = 0;
        }

        String provider() { return provider; }
        String model() { return model; }
        Duration inactivityTimeout() { return inactivityTimeout; }
        long lastActivity() { return lastActivity; }
        int messageCount() { return messageCount; }
        
        void updateLastActivity() { 
            this.lastActivity = System.currentTimeMillis(); 
        }
        
        void incrementMessageCount() {
            this.messageCount++;
        }
    }
}

