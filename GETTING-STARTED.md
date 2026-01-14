# Getting Started with Goose Agent Chat

This guide walks you through customizing Goose Agent Chat for your environment. You'll learn how to configure LLM providers, add MCP servers for extended capabilities, and set up skills for reusable workflows.

## Table of Contents

- [Configuration Overview](#configuration-overview)
- [Configuring LLM Providers](#configuring-llm-providers)
- [Adding MCP Servers](#adding-mcp-servers)
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

### Supported Providers

Goose supports multiple LLM providers:

| Provider | Environment Variable | Example Models |
|----------|---------------------|----------------|
| Anthropic | `ANTHROPIC_API_KEY` | claude-sonnet-4-20250514 |
| OpenAI | `OPENAI_API_KEY` | gpt-5.2-chat-latest |
| Google | `GOOGLE_API_KEY` | gemini-2.5-pro |
| Databricks | `DATABRICKS_HOST`, `DATABRICKS_TOKEN` | databricks-meta-llama-3-1-70b-instruct |
| Ollama | `OLLAMA_HOST` | llama3.1, codellama |

### Setting the Provider and Model

Edit `.goose-config.yml` to configure your preferred provider:

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

**For Cloud Foundry**, configure keys in `target/vars.yaml` (local/dev) or through CredHub (see [Cloud Foundry Deployment](#cloud-foundry-deployment)).

### Mailgun Configuration (for Email Sending)

The Mailgun skill allows Goose to send emails via the Mailgun API. Configure the following environment variables:

| Variable | Description | Example |
|----------|-------------|---------|
| `MAILGUN_API_KEY` | Your Mailgun API key | `key-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx` |
| `MAILGUN_DOMAIN` | Your Mailgun domain | `mg.yourdomain.com` or `mail.yourdomain.com` |
| `MAILGUN_FROM_EMAIL` | Sender email address | `Tanzu Agent <noreply@yourdomain.com>` |

**For local development**, set environment variables:

```bash
export MAILGUN_API_KEY=key-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export MAILGUN_DOMAIN=mg.yourdomain.com
export MAILGUN_FROM_EMAIL="Tanzu Agent <noreply@yourdomain.com>"
```

**For Cloud Foundry**, add to `target/vars.yaml`:

```yaml
MAILGUN_API_KEY: "key-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
MAILGUN_DOMAIN: "mg.yourdomain.com"
MAILGUN_FROM_EMAIL: "Tanzu Agent <noreply@yourdomain.com>"
```

The Mailgun skill is already configured in `.goose-config.yml` and will automatically use these environment variables when sending emails.

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
  # Cloud Foundry MCP server for CF operations
  - name: cloud-foundry
    type: streamable_http
    url: "https://cloud-foundry-mcp-server.apps.example.com/mcp"

  # GitHub MCP server for repository operations
  - name: github
    type: streamable_http
    url: "https://github-mcp-server.apps.example.com/mcp"
```

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

  # GitHub access via stdio (requires Node.js + token)
  - name: github
    type: stdio
    command: npx
    args:
      - "-y"
      - "@modelcontextprotocol/server-github"
    env:
      GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
```

> **Note:** For Cloud Foundry deployments, prefer `streamable_http` servers since `stdio` servers may require additional runtimes (Node.js, Python) that aren't included in the standard Java buildpack.

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

#### 2. Local File-Based Skills

Reference skills from directories bundled with your application:

```yaml
# .goose-config.yml
skills:
  - name: deployment
    path: .goose/skills/deployment
```

The skill directory must contain a `SKILL.md` file with YAML frontmatter:

```markdown
---
name: deployment
description: Deployment workflow for this project
---

# Deployment Steps
1. Build the application
2. Run integration tests
3. Deploy to staging
4. Verify health checks
5. Deploy to production
```

Place skill directories in `src/main/resources/skills/` so they're bundled in the JAR.

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
cf push --vars-file target/vars.yaml
```

#### What Happens During Deployment

1. **Upload** - Pushes the JAR file (`target/goose-agent-chat-1.0.0.jar`) to Cloud Foundry
2. **Staging** - Cloud Foundry runs the buildpacks:
   - `goose-buildpack` - Downloads and installs Goose CLI
   - `java_buildpack_offline` - Configures JRE 21 and starts the application
3. **Starting** - Application starts and listens on the assigned port
4. **Binding** - Cloud Foundry injects service credentials if services are bound

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
mvn clean package && cf push --vars-file target/vars.yaml
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
      
      # API key for your provider (use CredHub for production)
      ANTHROPIC_API_KEY: ((ANTHROPIC_API_KEY))
      # OPENAI_API_KEY: ((OPENAI_API_KEY))
      # GOOGLE_API_KEY: ((GOOGLE_API_KEY))
```

### Using vars.yaml for Secrets

For local deployments, create a `target/vars.yaml` file (excluded from git):

```yaml
ANTHROPIC_API_KEY: sk-ant-xxxxx
OPENAI_API_KEY: sk-xxxxx
```

Deploy with:

```bash
cf push --vars-file target/vars.yaml
```

### Environment Variables

| Variable | Description |
|----------|-------------|
| `GOOSE_ENABLED` | Enable Goose CLI integration |
| `GOOSE_CLI_PATH` | Path to Goose binary (set by buildpack) |
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

  - name: code-review
    description: Code review checklist
    content: |
      # Code Review Checklist
      - [ ] Code does what the PR claims
      - [ ] Edge cases handled
      - [ ] Follows project style guide

# MCP Servers for extended capabilities
mcpServers:
  - name: cloud-foundry
    type: streamable_http
    url: "https://cloud-foundry-mcp-server.apps.example.com/mcp"

  - name: github
    type: streamable_http
    url: "https://github-mcp-server.apps.example.com/mcp"
```

---

## Next Steps

- Review the [Goose Documentation](https://block.github.io/goose/) for advanced configuration options
- Explore the [MCP Server Registry](https://github.com/modelcontextprotocol/servers) for available servers
- Check the [Goose Skills Guide](https://block.github.io/goose/docs/guides/context-engineering/using-skills) for creating custom skills
