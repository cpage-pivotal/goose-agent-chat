# Goose Agent Chat

A full-stack web application providing a chat interface for interacting with [Goose AI agent](https://github.com/block/goose). Built with Spring Boot and Angular, featuring real-time streaming responses and Material Design 3 UI.

> **[Getting Started Guide](GETTING-STARTED.md)** — Learn how to configure LLM providers, add MCP servers, set up skills, and deploy to Cloud Foundry with Tanzu Marketplace integration.

## Features

- **Multi-turn Conversations**: Maintains conversation context across messages
- **Real-time Streaming**: SSE-based streaming of responses
- **Material Design 3**: Modern, responsive UI using Angular Material
- **Multi-Provider Support**: Works with Anthropic, OpenAI, Google, Databricks, and Ollama
- **Agent Credential Broker**: Centralized credential management for OAuth-protected MCP servers via delegation tokens
- **Authentication**: OIDC single sign-on (Keycloak) via OAuth2, with a shared-secret form-login fallback
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

All requests require authentication. The app boots in one of two modes, selected automatically at startup:

| Mode | When | Login |
|------|------|-------|
| `OAUTH2` | An OAuth2 client registration is configured (the `oauth` Spring profile is active) | OIDC provider (Keycloak) |
| `PASSWORD` | No client registration | Form login with a shared secret |

`AuthModeProvider` makes this decision by checking whether a `ClientRegistrationRepository` bean exists. `/auth/status` reports the active `mode` and the `loginUrl` the frontend should use.

### OAUTH2 mode

- Nothing in the code is provider-specific — plain `oauth2Login`, issuer-uri discovery, no role mapping. Keycloak is what's deployed in practice, but any OIDC provider works.
- Configured in `src/main/resources/application-oauth.properties`, loaded only under the `oauth` profile. Gating it behind a profile is what preserves the PASSWORD fallback: with no profile there is no client registration, so OAuth2 property validation never runs.
- Each user gets a unique identity via the `sub` claim, available through `/auth/status` as `userId`.

Set on Cloud Foundry via `manifest.yml` (values in `vars.yaml`):

```yaml
SPRING_PROFILES_ACTIVE: oauth
OIDC_ISSUER_URI: ((OIDC_ISSUER_URI))
OIDC_CLIENT_ID: ((OIDC_CLIENT_ID))
OIDC_CLIENT_SECRET: ((OIDC_CLIENT_SECRET))
```

Register `https://<app-route>/login/oauth2/code/sso` as a valid redirect URI with the provider. The provider should be the same one used by the Agent Credential Broker, so user identities are consistent across both apps.

### PASSWORD mode

Without the `oauth` profile, the app serves a form login for a single user `user`, with the password taken from `APP_AUTH_SECRET` (`app.auth.secret`, default `changeme`). This is the default for local development — `./mvnw spring-boot:run` needs no OIDC setup.

Setting `BROKER_BASE_URL` in PASSWORD mode is a **fail-fast startup error**: the Agent Credential Broker requires real user identities.

## Credential Management

OAuth credentials for MCP servers (GitHub, Cloud Foundry, etc.) are managed by the [Agent Credential Broker](../agent-credential-broker/), a standalone service that centralizes credential acquisition and delegation.

### How it works

1. A user pre-authorizes target systems (e.g., GitHub) in the Credential Broker's UI
2. At session creation, goose-agent-chat obtains a **delegation token** from the broker using the user's OIDC access token
3. Before each Goose execution, the delegation token is used to request short-lived **resource access tokens** from the broker
4. The broker returns the credential **and** the MCP server URL for each target system
5. Both are injected into Goose's `config.yaml` — the URL as the server endpoint and the credential as an `Authorization` header

### Configuration

Two environment variables are required to enable broker integration:

```yaml
# manifest.yml or vars.yaml

# Internal container-to-container (C2C) URL — used by goose-agent-chat to call the broker
# Routed over the apps.internal domain; requires a network policy (see below)
BROKER_BASE_URL: https://agent-credential-broker.apps.internal:8443

# Public URL — surfaced to the browser so users can navigate to the broker's grants UI
BROKER_PUBLIC_URL: https://agent-credential-broker.apps.example.com
```

After deploying both apps, allow goose-agent-chat to reach the broker over the internal network:

```bash
cf add-network-policy goose-agent-chat agent-credential-broker --protocol tcp --port 8443
```

This enables secure container-to-container communication on the `apps.internal` domain without exposing the broker's internal traffic through the public router.

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
| `BROKER_BASE_URL` | Internal C2C URL to Agent Credential Broker (apps.internal:8443) |
| `BROKER_PUBLIC_URL` | Public URL for broker grants UI (shown to users in the browser) |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `OPENAI_API_KEY` | OpenAI API key |
| `GOOGLE_API_KEY` | Google AI API key |
| `DATABRICKS_HOST` | Databricks workspace URL |
| `DATABRICKS_TOKEN` | Databricks access token |
| `GOOSE_PROVIDER__TYPE` | Default provider (anthropic, openai, etc.) |
| `GOOSE_PROVIDER__MODEL` | Default model |

## License

MIT License
