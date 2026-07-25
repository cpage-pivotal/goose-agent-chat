# Getting Started with Goose Agent Chat

This guide walks you through customizing Goose Agent Chat for your environment. You'll learn how to configure LLM providers, add MCP servers, set up credential management via the Agent Credential Broker, and deploy to Cloud Foundry.

## Table of Contents

- [Configuration Overview](#configuration-overview)
- [Configuring LLM Providers](#configuring-llm-providers)
- [Adding MCP Servers](#adding-mcp-servers)
- [Credential Management with the Agent Credential Broker](#credential-management-with-the-agent-credential-broker)
- [Configuring Skills](#configuring-skills)
- [Building and Deploying](#building-and-deploying)
- [Cloud Foundry Deployment](#cloud-foundry-deployment)
- [Tanzu Marketplace Integration](#tanzu-marketplace-integration)

---

## Configuration Overview

Goose Agent Chat uses two main configuration files:

| File | Purpose |
|------|---------|
| `.goose-config.yml` | Goose CLI configuration (providers, MCP servers, skills) |
| `manifest.yml` | Cloud Foundry deployment settings and environment variables |

The `.goose-config.yml` file is located in `src/main/resources/` and gets bundled into the application JAR during build.

---

## Configuring LLM Providers

There are two ways to configure which LLM model to use:

1. **Tanzu GenAI Service Binding** (recommended for Cloud Foundry) - Bind a GenAI service from Tanzu Marketplace, and models are automatically discovered
2. **Manual Configuration** - Set provider and model in `.goose-config.yml` with API keys

> **Note:** GenAI service bindings take precedence over manual configuration. See [Tanzu Marketplace Integration](#tanzu-marketplace-integration) for details.

### Supported Providers

Goose supports multiple LLM providers:

| Provider | Environment Variable | Example Models |
|----------|---------------------|----------------|
| Anthropic | `ANTHROPIC_API_KEY` | claude-sonnet-4-20250514 |
| OpenAI | `OPENAI_API_KEY` | gpt-5.2-chat-latest |
| Google | `GOOGLE_API_KEY` | gemini-2.5-pro |
| Databricks | `DATABRICKS_HOST`, `DATABRICKS_TOKEN` | databricks-meta-llama-3-1-70b-instruct |
| Ollama | `OLLAMA_HOST` | llama3.1, codellama |

### Setting the Provider and Model (Manual Configuration)

For manual configuration, edit `.goose-config.yml` to set your preferred provider:

```yaml
# LLM Provider configuration
provider: anthropic
model: claude-sonnet-4-20250514

# Or for OpenAI:
# provider: openai
# model: gpt-5.2-chat-latest
```

### Setting API Keys

**For local development**, set environment variables:

```bash
export ANTHROPIC_API_KEY=sk-ant-xxxxx
# Or for OpenAI:
# export OPENAI_API_KEY=sk-xxxxx
```

**For Cloud Foundry**, configure keys in `vars.yaml` or through CredHub (see [Cloud Foundry Deployment](#cloud-foundry-deployment)).

---

## Adding MCP Servers

[Model Context Protocol (MCP)](https://modelcontextprotocol.io/) servers extend Goose's capabilities by providing access to external tools and data sources.

### MCP Server Types

| Type | Transport | Use Case |
|------|-----------|----------|
| `streamable_http` | HTTP/SSE | Remote servers accessible over the network |
| `stdio` | Standard I/O | Local servers running as child processes |

### Adding Remote MCP Servers (Recommended for Cloud Foundry)

Remote MCP servers are ideal for Cloud Foundry deployments since they don't require additional binaries in the container.

```yaml
# .goose-config.yml
mcpServers:
  # Cloud Foundry MCP server - credentials and URL managed by Agent Credential Broker
  - name: cloud-foundry
    type: streamable_http
    brokerAuth: true

  # GitHub MCP server - credentials and URL managed by Agent Credential Broker
  - name: github
    type: streamable_http
    brokerAuth: true
```

For servers that require authentication, set `brokerAuth: true`. The `url` field can be omitted — the Agent Credential Broker provides both the credential and the MCP server URL at runtime. No `clientId`, `clientSecret`, or `scopes` are needed in the app configuration either — credential management is handled by the [Agent Credential Broker](#credential-management-with-the-agent-credential-broker).

### Adding Local MCP Servers

Local MCP servers run as child processes. These are useful for development but require the server binary/runtime to be available in the container.

```yaml
# .goose-config.yml
mcpServers:
  # Filesystem access (requires Node.js in container)
  - name: filesystem
    type: stdio
    command: npx
    args:
      - "-y"
      - "@modelcontextprotocol/server-filesystem"
    env:
      ALLOWED_DIRECTORIES: "/home/vcap/app,/tmp"
```

> **Note:** For Cloud Foundry deployments, prefer `streamable_http` servers since `stdio` servers may require additional runtimes (Node.js, Python) that aren't included in the standard Java buildpack.

---

## Credential Management with the Agent Credential Broker

OAuth credentials for MCP servers (GitHub, Cloud Foundry, etc.) are managed centrally by the [Agent Credential Broker](../agent-credential-broker/), a standalone service that handles credential acquisition and delegation.

### How It Works

1. **User grants access** — A user pre-authorizes target systems (e.g., GitHub, Cloud Foundry) in the Credential Broker's web UI
2. **Delegation token** — At session creation, goose-agent-chat obtains a signed delegation token from the broker, using the user's OIDC access token for authentication
3. **Credential injection** — Before each Goose execution, the delegation token is exchanged for short-lived access tokens for each MCP server. The broker also returns the MCP server URL for each target system.
4. **Transparent auth** — Access tokens and MCP server URLs are injected into Goose's `config.yaml`, so MCP servers receive authenticated requests at the correct endpoints

### Prerequisites

- The Agent Credential Broker must be deployed and accessible
- Both goose-agent-chat and the broker must authenticate against the same OIDC provider (same issuer and realm), so user identities are consistent
- goose-agent-chat must be running in OAUTH2 mode — setting `BROKER_BASE_URL` without the `oauth` profile is a fail-fast startup error
- The user must have active grants in the broker for the target systems they want to use

### Configuration

Two environment variables connect goose-agent-chat to the broker:

```yaml
# vars.yaml

# Internal container-to-container (C2C) URL used by the backend to call the broker.
# Routed over the apps.internal domain using mTLS — never hits the public router.
BROKER_BASE_URL: https://agent-credential-broker.apps.internal:8443

# Public URL surfaced to the browser so users can navigate to the broker's grants UI.
BROKER_PUBLIC_URL: https://agent-credential-broker.apps.example.com
```

After deploying both applications, enable the container-to-container network connection:

```bash
cf add-network-policy goose-agent-chat agent-credential-broker --protocol tcp --port 8443
```

This allows goose-agent-chat to reach the broker directly over the internal `apps.internal` network on port 8443. Without this policy, backend calls to `BROKER_BASE_URL` will time out even if the URL is correct.

The broker manages all OAuth client registrations, token storage, and refresh logic — no additional credential configuration is needed in goose-agent-chat.

### MCP Server Configuration

MCP servers that require authentication should have `brokerAuth: true` in `.goose-config.yml`. The `url` field can be omitted — the broker returns it at runtime alongside the credential:

```yaml
mcpServers:
  - name: github
    type: streamable_http
    brokerAuth: true
```

No `url`, `clientId`, `clientSecret`, or `scopes` fields are needed — the broker is the single source of truth for endpoint URLs and credential details.

### Verifying Broker Connectivity

After deployment, verify the broker connection:

```bash
curl https://goose-agent-chat.apps.example.com/api/broker/status
```

Expected response when properly configured:

```json
{
  "configured": true,
  "baseUrl": "https://agent-credential-broker.apps.example.com"
}
```

### Troubleshooting

#### MCP servers show as enabled but have no tools

The user likely hasn't granted access to the target system in the broker. Direct them to the broker UI to create grants.

#### "Extension not recognized" or authentication failures

Check `cf logs goose-agent-chat --recent` for broker-related errors. Common causes:

- `BROKER_BASE_URL` not set or pointing to an incorrect URL
- The broker is down or unreachable
- The user's delegation token has expired (tokens are re-acquired per session)
- The OIDC issuer differs between goose-agent-chat and the broker

---

## Configuring Skills

Skills are reusable instruction sets that teach Goose how to perform specific tasks. They follow the [Goose Skills format](https://block.github.io/goose/docs/guides/context-engineering/using-skills).

### Skill Types

#### 1. Remote Git-Based Skills

Clone skills from a Git repository during Cloud Foundry staging:

```yaml
# .goose-config.yml
skills:
  - name: cf-space-auditor
    source: https://github.com/org/goose-skills.git
    branch: main
    path: plugins/cf-space-auditor/skills/cf-space-auditor

  - name: company-standards
    source: https://github.com/org/goose-skills.git
    branch: main
    path: skills/company-standards
```

> **Note:** Git-based skills require network access during Cloud Foundry staging.

#### 2. Local File-Based Skills (Bundled in the JAR)

Reference skills from directories bundled with your application:

```yaml
# .goose-config.yml
skills:
  - name: my-skill
    path: goose/skills/my-skill
```

The skill directory must contain a `SKILL.md` file with YAML frontmatter:

```markdown
---
name: my-skill
description: What this skill does
---

# My Skill

Instructions for Goose...
```

Place skill directories under `src/main/resources/` so they're bundled into the JAR. For example, a skill at `src/main/resources/goose/skills/my-skill/SKILL.md` is referenced as `path: goose/skills/my-skill`.

> **How it works:** The goose-buildpack runs before the Java buildpack, so the JAR hasn't been exploded yet when skills are configured. The buildpack automatically extracts `path:`-only skills from `BOOT-INF/classes/` inside the JAR before configuring them — no extra setup required. You'll see lines like `Extracted skill path from JAR: goose/skills/my-skill` in the staging log.

#### 3. Inline Skills

Embed skill content directly in the configuration:

```yaml
# .goose-config.yml
skills:
  - name: code-review
    description: Comprehensive code review checklist
    content: |
      # Code Review Checklist
      When reviewing code, check each of these areas:
      
      ## Functionality
      - [ ] Code does what the PR description claims
      - [ ] Edge cases are handled properly
      
      ## Code Quality
      - [ ] Follows project style guide
      - [ ] No hardcoded values that should be configurable
      - [ ] Appropriate error handling
```

---

## Building and Deploying

### Building the Application

Build the application using Maven, which compiles the Java backend and Angular frontend:

```bash
mvn clean package
```

This command:

1. **Compiles Java sources** - Builds the Spring Boot application
2. **Installs Node.js** - Downloads Node.js v22.12.0 to `target/` directory
3. **Installs npm dependencies** - Runs `npm ci` in `src/main/frontend/`
4. **Builds Angular frontend** - Runs `npm run build` to create production bundle
5. **Copies frontend assets** - Places built Angular app into `target/classes/static/`
6. **Packages JAR** - Creates `target/goose-agent-chat-1.0.0.jar` with embedded frontend

The resulting JAR file contains both the Spring Boot backend and the Angular frontend as static resources.

### Build Dependencies

The build process pulls dependencies from these repositories:

| Artifact | Repository | Purpose |
|----------|-----------|---------|
| `goose-cf-wrapper` | `us-central1-maven.pkg.dev/cf-mcp/maven-public` | Goose CLI wrapper for Cloud Foundry |
| `java-cfenv-boot-tanzu-genai` | Maven Central | Tanzu GenAI service binding support |
| Spring Boot dependencies | Maven Central | Framework dependencies |

> **Note:** The `goose-cf-wrapper` artifact is hosted in a custom GCP Artifact Registry and is automatically resolved during the build.

### Deploying to Cloud Foundry

Once built, deploy the application to Cloud Foundry:

```bash
cf push
```

Or with variable substitution for secrets:

```bash
cf push --vars-file vars.yaml
```

#### What Happens During Deployment

1. **Upload** - Pushes the JAR file (`target/goose-agent-chat-1.0.0.jar`) to Cloud Foundry
2. **Staging** - Cloud Foundry runs the buildpacks:
   - `goose-buildpack` - Downloads and installs Goose CLI
   - `java_buildpack_offline` - Configures JRE 21 and starts the application
3. **Starting** - Application starts and listens on the assigned port
4. **Binding** - Cloud Foundry injects service credentials if services are bound

#### Enabling Broker Connectivity

If you are using the Agent Credential Broker, add the container-to-container network policy after both apps are deployed:

```bash
cf add-network-policy goose-agent-chat agent-credential-broker --protocol tcp --port 8443
```

This must be run once per environment. Without it, goose-agent-chat cannot reach the broker's internal `apps.internal` address and session creation will fail.

#### Verifying the Deployment

After deployment completes, verify the application is running:

```bash
# Check application status
cf app goose-agent-chat

# View logs
cf logs goose-agent-chat --recent

# Test the health endpoint
curl https://goose-agent-chat.apps.example.com/api/chat/health
```

Expected health response:

```json
{
  "status": "healthy",
  "provider": "anthropic",
  "model": "claude-sonnet-4-20250514",
  "source": "environment"
}
```

#### Redeploying After Configuration Changes

After modifying `.goose-config.yml` or other resources:

```bash
# Rebuild and redeploy in one step
mvn clean package && cf push --vars-file vars.yaml
```

---

## Cloud Foundry Deployment

### Customizing manifest.yml

The `manifest.yml` file controls Cloud Foundry deployment settings:

```yaml
applications:
  - name: goose-agent-chat
    path: target/goose-agent-chat-1.0.0.jar
    memory: 2G
    buildpacks:
      - https://github.com/cpage-pivotal/goose-buildpack
      - java_buildpack_offline
    env:
      JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 21.+ } }'
      GOOSE_ENABLED: true

      # OIDC login — without these the app falls back to shared-secret form login
      SPRING_PROFILES_ACTIVE: oauth
      OIDC_ISSUER_URI: ((OIDC_ISSUER_URI))
      OIDC_CLIENT_ID: ((OIDC_CLIENT_ID))
      OIDC_CLIENT_SECRET: ((OIDC_CLIENT_SECRET))

      BROKER_BASE_URL: ((BROKER_BASE_URL))
      
      # API key for your provider (use CredHub for production)
      # ANTHROPIC_API_KEY: ((ANTHROPIC_API_KEY))
      # OPENAI_API_KEY: ((OPENAI_API_KEY))
```

### Using vars.yaml for Secrets

For local deployments, create a `vars.yaml` file (excluded from git):

```yaml
BROKER_BASE_URL: https://agent-credential-broker.apps.example.com
ANTHROPIC_API_KEY: sk-ant-xxxxx
```

Deploy with:

```bash
cf push --vars-file vars.yaml
```

### Environment Variables

| Variable | Description |
|----------|-------------|
| `GOOSE_ENABLED` | Enable Goose CLI integration |
| `GOOSE_CLI_PATH` | Path to Goose binary (set by buildpack) |
| `BROKER_BASE_URL` | Agent Credential Broker URL (enables broker integration) |
| `GOOSE_PROVIDER` | Override default provider |
| `GOOSE_MODEL` | Override default model |
| `GOOSE_TIMEOUT_MINUTES` | Execution timeout (default: 5) |
| `GOOSE_MAX_TURNS` | Max conversation turns (default: 100) |

---

## Tanzu Marketplace Integration

When deploying to Cloud Foundry with Tanzu Marketplace, you can bind to GenAI services that provide chat models. **Bound GenAI services take precedence over locally configured models.**

### How It Works

1. **Service Binding**: When a GenAI service is bound to your application, Cloud Foundry injects credentials via `VCAP_SERVICES`

2. **Auto-Discovery**: The application automatically discovers available TOOLS-capable models from the bound service

3. **Precedence**: GenAI service models override any provider/model configuration in `.goose-config.yml` or environment variables

### Binding a GenAI Service

```bash
# Create a GenAI service instance from Tanzu Marketplace
cf create-service genai standard my-genai-service

# Bind to your application
cf bind-service goose-agent-chat my-genai-service

# Restage to pick up the binding
cf restage goose-agent-chat
```

### Configuration Priority

The application determines which model to use in this order:

1. **GenAI Service Binding** (highest priority) - Models from Tanzu Marketplace
2. **Session Configuration** - Provider/model specified when creating a chat session
3. **Environment Variables** - `GOOSE_PROVIDER`, `GOOSE_MODEL`
4. **Configuration File** - Settings in `.goose-config.yml`

### Verifying the Active Model

Use the health endpoint to see which model is active:

```bash
curl https://goose-agent-chat.apps.example.com/api/chat/health
```

Response:

```json
{
  "status": "healthy",
  "provider": "openai",
  "model": "gpt-4-turbo",
  "source": "genai-service"
}
```

The `source` field indicates whether the model is from `genai-service` or `environment`.

### Bypassing GenAI Service

To temporarily use locally configured models instead of the bound GenAI service:

```bash
cf set-env goose-agent-chat BYPASS_GENAI true
cf restage goose-agent-chat
```

---

## Complete Configuration Example

Here's a complete `.goose-config.yml` with all features:

```yaml
# Goose Configuration for goose-agent-chat
# See: https://block.github.io/goose/docs/guides/configuration-files/

# LLM Provider (overridden by GenAI service binding if present)
provider: anthropic
model: claude-sonnet-4-20250514

# Enable developer extension for file/shell access
extensions:
  developer:
    enabled: true

# Session defaults
session:
  max_turns: 100

# Remote skills from Git repositories
skills:
  - name: cf-space-auditor
    source: https://github.com/org/goose-skills.git
    branch: main
    path: plugins/cf-space-auditor/skills/cf-space-auditor

  # Local skill bundled in the JAR (src/main/resources/goose/skills/my-skill/)
  - name: my-skill
    path: goose/skills/my-skill

  - name: code-review
    description: Code review checklist
    content: |
      # Code Review Checklist
      - [ ] Code does what the PR claims
      - [ ] Edge cases handled
      - [ ] Follows project style guide

# MCP Servers for extended capabilities
# Credentials and URLs managed by Agent Credential Broker (set BROKER_BASE_URL)
mcpServers:
  # GitHub MCP server — URL and credentials provided by broker at runtime
  - name: github
    type: streamable_http
    brokerAuth: true

  # Cloud Foundry MCP server — URL and credentials provided by broker at runtime
  - name: cloud-foundry
    type: streamable_http
    brokerAuth: true
  
  # Public MCP server (no authentication needed — url required here)
  - name: internal-tools
    type: streamable_http
    url: "https://internal-mcp.apps.example.com/mcp"
```

And the corresponding `vars.yaml` for secrets:

```yaml
# Internal C2C URL — requires cf add-network-policy (see Credential Management section)
BROKER_BASE_URL: https://agent-credential-broker.apps.internal:8443
# Public URL — shown to users when they need to manage their grants
BROKER_PUBLIC_URL: https://agent-credential-broker.apps.example.com
ANTHROPIC_API_KEY: sk-ant-xxxxx
```

---

## Next Steps

- Review the [Goose Documentation](https://block.github.io/goose/) for advanced configuration options
- Explore the [MCP Server Registry](https://github.com/modelcontextprotocol/servers) for available servers
- Check the [Goose Skills Guide](https://block.github.io/goose/docs/guides/context-engineering/using-skills) for creating custom skills
- Set up the [Agent Credential Broker](../agent-credential-broker/) for centralized credential management
