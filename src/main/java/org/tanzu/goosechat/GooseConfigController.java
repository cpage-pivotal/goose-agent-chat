package org.tanzu.goosechat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tanzu.goose.cf.GooseConfiguration;
import org.tanzu.goose.cf.GooseExecutor;

import java.util.List;
import java.util.Optional;

/**
 * REST controller for exposing Goose configuration (skills and MCP servers).
 * <p>
 * This endpoint provides the frontend with information about the configured
 * skills and MCP servers loaded from the Goose configuration file.
 * </p>
 */
@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "*")
public class GooseConfigController {

    private final Optional<GooseExecutor> executor;

    public GooseConfigController(@Autowired(required = false) GooseExecutor executor) {
        this.executor = Optional.ofNullable(executor);
    }

    /**
     * Get the Goose configuration including skills and MCP servers.
     *
     * @return the configuration response with skills and MCP servers
     */
    @GetMapping
    public ConfigResponse getConfiguration() {
        if (executor.isEmpty()) {
            return new ConfigResponse(
                null,
                null,
                List.of(),
                List.of(),
                "Goose executor not available"
            );
        }

        GooseConfiguration config = executor.get().getConfiguration();
        
        List<SkillResponse> skills = config.skills().stream()
            .map(s -> new SkillResponse(
                s.name(),
                s.description(),
                s.source(),
                s.path(),
                s.repository(),
                s.branch()
            ))
            .toList();
        
        List<McpServerResponse> mcpServers = config.mcpServers().stream()
            .map(m -> new McpServerResponse(
                m.name(),
                m.type(),
                m.url(),
                m.command(),
                m.args(),
                m.requiresAuth()
            ))
            .toList();

        return new ConfigResponse(
            config.provider(),
            config.model(),
            skills,
            mcpServers,
            null
        );
    }

    /**
     * Configuration response containing skills and MCP servers.
     */
    public record ConfigResponse(
        String provider,
        String model,
        List<SkillResponse> skills,
        List<McpServerResponse> mcpServers,
        String error
    ) {}

    /**
     * Skill information for the frontend.
     */
    public record SkillResponse(
        String name,
        String description,
        String source,
        String path,
        String repository,
        String branch
    ) {}

    /**
     * MCP server information for the frontend.
     */
    public record McpServerResponse(
        String name,
        String type,
        String url,
        String command,
        List<String> args,
        boolean requiresAuth
    ) {}
}
