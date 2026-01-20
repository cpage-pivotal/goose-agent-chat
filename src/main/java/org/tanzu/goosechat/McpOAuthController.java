package org.tanzu.goosechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tanzu.goose.cf.GooseConfiguration;
import org.tanzu.goose.cf.GooseExecutor;
import org.tanzu.goose.cf.McpServerInfo;
import org.tanzu.goose.cf.oauth.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller for MCP server OAuth authentication flows.
 * <p>
 * This controller provides endpoints for:
 * <ul>
 *   <li>Checking OAuth authentication status for MCP servers</li>
 *   <li>Initiating OAuth authorization flows</li>
 *   <li>Handling OAuth callbacks</li>
 *   <li>Disconnecting (revoking tokens)</li>
 * </ul>
 * </p>
 * <p>
 * Also serves the OAuth Client ID Metadata Document as required by the MCP
 * Authorization specification.
 * </p>
 */
@RestController
@CrossOrigin(origins = "*")
public class McpOAuthController {

    private static final Logger logger = LoggerFactory.getLogger(McpOAuthController.class);

    private final GooseExecutor executor;
    private final McpOAuthManager oauthManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${goose.oauth.client-name:Goose Agent Chat}")
    private String clientName;

    // Cache for MCP server configurations
    private final Map<String, McpServerInfo> serverCache = new ConcurrentHashMap<>();

    public McpOAuthController(GooseExecutor executor) {
        this.executor = executor;
        // Initialize OAuth manager with a placeholder - actual client ID URL will be determined per request
        this.oauthManager = new McpOAuthManagerImpl("placeholder");
    }

    /**
     * Serve the OAuth Client ID Metadata Document.
     * <p>
     * This document describes this application as an OAuth client to authorization servers.
     * The URL of this endpoint becomes the client_id in OAuth flows.
     * </p>
     */
    @GetMapping(value = "/oauth/client-metadata.json", produces = "application/json")
    public ResponseEntity<ClientMetadata> getClientMetadata(HttpServletRequest request) {
        String baseUrl = detectBaseUrl(request);
        String clientId = baseUrl + "/oauth/client-metadata.json";
        String redirectUri = baseUrl + "/oauth/callback";

        ClientMetadata metadata = new ClientMetadata(
            clientId,
            clientName,
            baseUrl,
            List.of(redirectUri),
            List.of("authorization_code"),
            List.of("code"),
            "none"
        );

        logger.debug("Serving client metadata: clientId={}", clientId);
        return ResponseEntity.ok(metadata);
    }

    /**
     * Check OAuth authentication status for an MCP server.
     *
     * @param serverName the MCP server name
     * @param sessionId the user's session ID
     * @return authentication status
     */
    @GetMapping("/api/mcp/{serverName}/auth/status")
    public ResponseEntity<AuthStatusResponse> getAuthStatus(
            @PathVariable String serverName,
            @RequestParam String sessionId) {
        
        boolean authenticated = oauthManager.isAuthenticated(serverName, sessionId);
        
        return ResponseEntity.ok(new AuthStatusResponse(
            serverName,
            authenticated,
            authenticated ? "Connected" : "Not connected"
        ));
    }

    /**
     * Initiate OAuth authorization for an MCP server.
     * <p>
     * This endpoint:
     * 1. Discovers OAuth configuration from the MCP server
     * 2. Generates PKCE parameters and state
     * 3. Returns the authorization URL for the client to redirect to
     * </p>
     *
     * @param serverName the MCP server name
     * @param sessionId the user's session ID
     * @return authorization URL and state
     */
    @GetMapping("/api/mcp/{serverName}/auth/initiate")
    public ResponseEntity<InitiateAuthResponse> initiateAuth(
            @PathVariable String serverName,
            @RequestParam String sessionId,
            HttpServletRequest request) {
        
        logger.info("Initiating OAuth for server: {} session: {}", serverName, sessionId);
        
        try {
            // Find the MCP server configuration
            McpServerInfo serverInfo = findMcpServer(serverName);
            if (serverInfo == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new InitiateAuthResponse(null, null, "MCP server not found: " + serverName));
            }
            
            if (!serverInfo.isHttpServer()) {
                return ResponseEntity.badRequest()
                    .body(new InitiateAuthResponse(null, null, "OAuth is only supported for HTTP-based MCP servers"));
            }
            
            String baseUrl = detectBaseUrl(request);
            String clientIdUrl = baseUrl + "/oauth/client-metadata.json";
            String redirectUri = baseUrl + "/oauth/callback";
            
            // Create a new OAuth manager with the correct client ID URL
            McpOAuthManagerImpl manager = new McpOAuthManagerImpl(clientIdUrl);
            
            // Discover OAuth configuration
            McpOAuthConfig config = manager.discoverOAuthConfig(serverName, serverInfo.url()).join();
            
            // Initiate authorization
            McpOAuthManager.OAuthAuthorizationRequest authRequest = 
                manager.initiateAuthorization(config, sessionId, redirectUri);
            
            logger.info("Generated auth URL for server: {}", serverName);
            
            return ResponseEntity.ok(new InitiateAuthResponse(
                authRequest.authorizationUrl(),
                authRequest.state(),
                null
            ));
            
        } catch (Exception e) {
            logger.error("Failed to initiate OAuth for server: {}", serverName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new InitiateAuthResponse(null, null, "OAuth initiation failed: " + e.getMessage()));
        }
    }

    /**
     * Handle OAuth callback.
     * <p>
     * This endpoint receives the authorization code from the OAuth provider
     * and exchanges it for tokens.
     * </p>
     *
     * @param code the authorization code
     * @param state the state parameter for CSRF validation
     * @return success or error response
     */
    @GetMapping("/oauth/callback")
    public ResponseEntity<String> handleCallback(
            @RequestParam String code,
            @RequestParam String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription) {
        
        if (error != null) {
            logger.error("OAuth callback error: {} - {}", error, errorDescription);
            return ResponseEntity.ok(buildCallbackHtml(false, error, errorDescription));
        }
        
        logger.info("Received OAuth callback with state: {}", state);
        
        try {
            // Exchange code for tokens
            McpOAuthTokens tokens = oauthManager.exchangeCodeForTokens(state, code).join();
            
            logger.info("Successfully obtained tokens for server: {}", tokens.serverName());
            
            return ResponseEntity.ok(buildCallbackHtml(true, tokens.serverName(), null));
            
        } catch (Exception e) {
            logger.error("OAuth callback failed", e);
            return ResponseEntity.ok(buildCallbackHtml(false, "Token exchange failed", e.getMessage()));
        }
    }

    /**
     * API endpoint for processing OAuth callback (called from frontend).
     */
    @PostMapping("/api/mcp/auth/callback")
    public ResponseEntity<CallbackResponse> processCallback(@RequestBody CallbackRequest request) {
        logger.info("Processing OAuth callback for state: {}", request.state());
        
        try {
            McpOAuthTokens tokens = oauthManager.exchangeCodeForTokens(request.state(), request.code()).join();
            
            return ResponseEntity.ok(new CallbackResponse(
                true,
                tokens.serverName(),
                null
            ));
            
        } catch (Exception e) {
            logger.error("OAuth callback processing failed", e);
            return ResponseEntity.ok(new CallbackResponse(
                false,
                null,
                "Token exchange failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Disconnect (revoke tokens) for an MCP server.
     *
     * @param serverName the MCP server name
     * @param sessionId the user's session ID
     * @return success response
     */
    @PostMapping("/api/mcp/{serverName}/auth/disconnect")
    public ResponseEntity<DisconnectResponse> disconnect(
            @PathVariable String serverName,
            @RequestParam String sessionId) {
        
        logger.info("Disconnecting OAuth for server: {} session: {}", serverName, sessionId);
        
        oauthManager.revokeTokens(serverName, sessionId);
        
        return ResponseEntity.ok(new DisconnectResponse(true, "Disconnected successfully"));
    }

    /**
     * Get the access token for an MCP server (internal use).
     */
    public Optional<String> getAccessToken(String serverName, String sessionId) {
        return oauthManager.getAccessToken(serverName, sessionId);
    }

    /**
     * Find an MCP server by name from configuration.
     */
    private McpServerInfo findMcpServer(String serverName) {
        // Check cache first
        McpServerInfo cached = serverCache.get(serverName);
        if (cached != null) {
            return cached;
        }
        
        // Load from configuration
        GooseConfiguration config = executor.getConfiguration();
        for (McpServerInfo server : config.mcpServers()) {
            serverCache.put(server.name(), server);
            if (server.name().equals(serverName)) {
                return server;
            }
        }
        
        return null;
    }

    /**
     * Detect the base URL of this application from the request.
     * <p>
     * Priority:
     * 1. VCAP_APPLICATION (Cloud Foundry)
     * 2. X-Forwarded headers (reverse proxy)
     * 3. Request URL (direct access)
     * </p>
     */
    private String detectBaseUrl(HttpServletRequest request) {
        // Try Cloud Foundry VCAP_APPLICATION
        String vcapApp = System.getenv("VCAP_APPLICATION");
        if (vcapApp != null && !vcapApp.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> vcap = objectMapper.readValue(vcapApp, Map.class);
                @SuppressWarnings("unchecked")
                List<String> uris = (List<String>) vcap.get("application_uris");
                if (uris != null && !uris.isEmpty()) {
                    return "https://" + uris.get(0);
                }
            } catch (Exception e) {
                logger.warn("Failed to parse VCAP_APPLICATION", e);
            }
        }
        
        // Try X-Forwarded headers
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (forwardedHost != null && !forwardedHost.isEmpty()) {
            String proto = forwardedProto != null ? forwardedProto : "https";
            return proto + "://" + forwardedHost;
        }
        
        // Fall back to request URL
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        
        StringBuilder url = new StringBuilder();
        url.append(scheme).append("://").append(serverName);
        if ((scheme.equals("http") && serverPort != 80) || 
            (scheme.equals("https") && serverPort != 443)) {
            url.append(":").append(serverPort);
        }
        
        return url.toString();
    }

    /**
     * Build HTML page for OAuth callback (displayed in popup/redirect).
     */
    private String buildCallbackHtml(boolean success, String serverOrError, String details) {
        String title = success ? "Authentication Successful" : "Authentication Failed";
        String message = success 
            ? "Successfully connected to " + serverOrError + ". You can close this window."
            : "Error: " + serverOrError + (details != null ? " - " + details : "");
        String bgColor = success ? "#4CAF50" : "#f44336";
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>%s</title>
                <style>
                    body { font-family: Arial, sans-serif; display: flex; justify-content: center; 
                           align-items: center; height: 100vh; margin: 0; background: #f5f5f5; }
                    .card { background: white; padding: 40px; border-radius: 8px; 
                            box-shadow: 0 2px 10px rgba(0,0,0,0.1); text-align: center; max-width: 400px; }
                    .icon { font-size: 48px; margin-bottom: 20px; }
                    h1 { color: %s; margin-bottom: 10px; }
                    p { color: #666; }
                    button { margin-top: 20px; padding: 10px 20px; background: %s; color: white;
                             border: none; border-radius: 4px; cursor: pointer; font-size: 16px; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="icon">%s</div>
                    <h1>%s</h1>
                    <p>%s</p>
                    <button onclick="window.close()">Close Window</button>
                </div>
                <script>
                    // Notify parent window
                    if (window.opener) {
                        window.opener.postMessage({ type: 'oauth-callback', success: %s, server: '%s' }, '*');
                    }
                </script>
            </body>
            </html>
            """.formatted(
                title, 
                bgColor, 
                bgColor,
                success ? "✓" : "✗",
                title, 
                message,
                success,
                success ? serverOrError : ""
            );
    }

    // Request/Response records

    public record ClientMetadata(
        String client_id,
        String client_name,
        String client_uri,
        List<String> redirect_uris,
        List<String> grant_types,
        List<String> response_types,
        String token_endpoint_auth_method
    ) {}

    public record AuthStatusResponse(
        String serverName,
        boolean authenticated,
        String message
    ) {}

    public record InitiateAuthResponse(
        String authUrl,
        String state,
        String error
    ) {}

    public record CallbackRequest(
        String code,
        String state
    ) {}

    public record CallbackResponse(
        boolean success,
        String serverName,
        String error
    ) {}

    public record DisconnectResponse(
        boolean success,
        String message
    ) {}
}
