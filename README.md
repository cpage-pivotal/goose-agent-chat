# Goose Agent Chat

A full-stack web application providing a chat interface for interacting with [Goose AI agent](https://github.com/block/goose). Built with Spring Boot and Angular, featuring real-time streaming responses and Material Design 3 UI.

> **[Getting Started Guide](GETTING-STARTED.md)** — Learn how to configure LLM providers, add MCP servers, set up skills, and deploy to Cloud Foundry with Tanzu Marketplace integration.

## Features

- **Multi-turn Conversations**: Maintains conversation context across messages
- **Real-time Streaming**: SSE-based streaming of responses
- **Material Design 3**: Modern, responsive UI using Angular Material
- **Multi-Provider Support**: Works with Anthropic, OpenAI, Google, Databricks, and Ollama
- **Agent Credential Broker**: Centralized credential management for OAuth-protected MCP servers via delegation tokens
- **Authentication**: Tanzu SSO (p-identity) single sign-on via OAuth2
- **Cloud Foundry Ready**: Deployable with the Goose buildpack

## Prerequisites

- Java 21+
- Maven 3.8+
- Node.js 22+ (managed by Maven during build)
- Goose CLI (installed via buildpack or locally)
- An API key for your chosen LLM provider

## Local Development

### 1. Set Environment Variables

```bash
# Set your preferred provider's API key
export ANTHROPIC_API_KEY=your-api-key
# Or for OpenAI:
# export OPENAI_API_KEY=your-api-key

# Set the path to Goose CLI (if not in PATH)
export GOOSE_CLI_PATH=/path/to/goose
```

### 2. Build and Run

```bash
# Build the application (includes Angular frontend)
./mvnw clean package

# Run the application
./mvnw spring-boot:run
```

### 3. Access the Application

Open http://localhost:8080 in your browser.

### Frontend Development

For faster frontend development with hot reload:

```bash
# Terminal 1: Start the Spring Boot backend
./mvnw spring-boot:run

# Terminal 2: Start Angular dev server
cd src/main/frontend
npm install
npm start
```

The Angular dev server runs on http://localhost:4200 and proxies API requests to the Spring Boot backend.

## Cloud Foundry Deployment

### 1. Create vars.yaml

```yaml
BROKER_BASE_URL: https://agent-credential-broker.apps.example.com
```

### 2. Deploy

```bash
# Build the application
./mvnw clean package -DskipTests

# Deploy to Cloud Foundry
cf push --vars-file vars.yaml
```

## Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Cloud Foundry Container                                                   │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐ │
│  │  Spring Boot Application (JAR)                                        │ │
│  │                                                                        │ │
│  │  ┌─────────────────────┐      ┌────────────────────────────────────┐ │ │
│  │  │  Angular SPA        │      │  REST Controllers                  │ │ │
│  │  │  /static/*          │─────▶│  GooseChatController               │ │ │
│  │  │  Material Design 3  │ HTTP │  ChatHealthController              │ │ │
│  │  │                     │      │  BrokerStatusController            │ │ │
│  │  └─────────────────────┘      └────────────────────────────────────┘ │ │
│  │                                          │                            │ │
│  │                                          ▼                            │ │
│  │                               ┌────────────────────────┐              │ │
│  │                               │  GooseExecutor         │              │ │
│  │                               │  (goose-cf-wrapper)    │              │ │
│  │                               │  - Session management  │              │ │
│  │                               │  - Broker credential   │              │ │
│  │                               │    injection           │              │ │
│  │                               └────────────────────────┘              │ │
│  │                                          │                            │ │
│  └──────────────────────────────────────────│────────────────────────────┘ │
│                                             │                              │
│  ┌──────────────────────────────────────────│────────────────────────────┐ │
│  │  Goose Buildpack (Supply)                ▼                            │ │
│  │  /home/vcap/deps/{idx}/bin/goose ◄───────────────────────────────────│ │
│  │  Environment: GOOSE_CLI_PATH, provider config                         │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
└──────────────────────┬─────────────────────────────┬──────────────────────┘
                       │                             │
                       ▼                             ▼
         ┌─────────────────────────┐   ┌─────────────────────────┐
         │   LLM Provider API      │   │ Agent Credential Broker  │
         │   (Anthropic, OpenAI,   │   │ (delegation tokens,      │
         │    Google, Databricks)  │   │  OAuth grants)           │
         └─────────────────────────┘   └─────────────────────────┘
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/auth/status` | GET | Returns current authentication state, userId (sub claim), and user info |
| `/logout` | POST | End the current session |
| `/api/chat/health` | GET | Check Goose availability and version |
| `/api/chat/sessions` | POST | Create a new conversation session |
| `/api/chat/sessions/{id}/messages` | POST | Send message (returns SSE stream) |
| `/api/chat/sessions/{id}/status` | GET | Check session status |
| `/api/chat/sessions/{id}` | DELETE | Close a session |
| `/api/broker/status` | GET | Check Agent Credential Broker configuration and availability |
| `/api/config` | GET | View current Goose configuration (provider, MCP servers) |
| `/api/diagnostics/env` | GET | View relevant environment variables |

## Authentication

All requests require authentication via Tanzu SSO (`p-identity` service binding).

### How it works

- Spring Security is configured with `oauth2Login` as the sole authentication mechanism. Unauthenticated requests are redirected to the SSO authorization endpoint.
- The `java-cfenv-boot-pivotal-sso` library auto-configures the OAuth2 client registration from the `p-identity` service binding in `VCAP_SERVICES`.
- Each user gets a unique identity via the `sub` claim from UAA, available through the `/auth/status` endpoint as `userId`.

### Setting up SSO on Cloud Foundry

The app requires a `p-identity` service instance bound as `agent-sso` in `manifest.yml`. This should be the same SSO instance used by the Agent Credential Broker, so that user identities are consistent across both apps.

```bash
# Create an SSO service instance (plan name may vary by foundation)
cf create-service p-identity <plan> agent-sso

# Deploy (manifest.yml already declares the agent-sso service binding)
cf push --vars-file vars.yaml
```

### Local development

For local development without a `p-identity` binding, configure a Spring Security OAuth2 client registration manually in `application.properties` or use a local OAuth2 provider.

## Credential Management

OAuth credentials for MCP servers (GitHub, Cloud Foundry, etc.) are managed by the [Agent Credential Broker](../agent-credential-broker/), a standalone service that centralizes credential acquisition and delegation.

### How it works

1. A user pre-authorizes target systems (e.g., GitHub) in the Credential Broker's UI
2. At session creation, goose-agent-chat obtains a **delegation token** from the broker using the user's UAA access token
3. Before each Goose execution, the delegation token is used to request short-lived **resource access tokens** from the broker
4. The broker returns the credential **and** the MCP server URL for each target system
5. Both are injected into Goose's `config.yaml` — the URL as the server endpoint and the credential as an `Authorization` header

### Configuration

Set the `BROKER_BASE_URL` environment variable to enable broker integration:

```yaml
# manifest.yml or vars.yaml
BROKER_BASE_URL: https://agent-credential-broker.apps.example.com
```

MCP servers that require authentication should have `brokerAuth: true` in `.goose-config.yml`. The `url` field can be omitted — the broker provides the MCP server URL at runtime alongside the credential. No `clientId`, `clientSecret`, or `scopes` are needed either — those are all managed by the broker.

```yaml
mcpServers:
  - name: github
    type: streamable_http
    brokerAuth: true
  - name: cloud-foundry
    type: streamable_http
    brokerAuth: true
```

## Configuration

### Application Properties

| Property | Default | Description |
|----------|---------|-------------|
| `goose.enabled` | `true` | Enable/disable Goose integration |
| `broker.base-url` | | Agent Credential Broker URL (enables broker integration) |

### Environment Variables

| Variable | Description |
|----------|-------------|
| `GOOSE_CLI_PATH` | Path to Goose CLI binary |
| `BROKER_BASE_URL` | Agent Credential Broker URL |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `OPENAI_API_KEY` | OpenAI API key |
| `GOOGLE_API_KEY` | Google AI API key |
| `DATABRICKS_HOST` | Databricks workspace URL |
| `DATABRICKS_TOKEN` | Databricks access token |
| `GOOSE_PROVIDER__TYPE` | Default provider (anthropic, openai, etc.) |
| `GOOSE_PROVIDER__MODEL` | Default model |

## License

MIT License
