# Goose Agent Chat

A full-stack web application providing a chat interface for interacting with [Goose AI agent](https://github.com/block/goose). Built with Spring Boot and Angular, featuring real-time streaming responses and Material Design 3 UI.

> **📘 [Getting Started Guide](GETTING-STARTED.md)** — Learn how to configure LLM providers, add MCP servers, set up skills, and deploy to Cloud Foundry with Tanzu Marketplace integration.

## Features

- **Multi-turn Conversations**: Maintains conversation context across messages
- **Real-time Streaming**: SSE-based streaming of responses
- **Material Design 3**: Modern, responsive UI using Angular Material
- **Multi-Provider Support**: Works with Anthropic, OpenAI, Google, Databricks, and Ollama
- **MCP OAuth2 Authentication**: Connect to OAuth-protected MCP servers with user consent flow
- **Authentication**: Always-on access code auth with optional SSO when a CF identity provider is bound
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
ANTHROPIC_API_KEY: your-api-key
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
│  │  │                     │      │  DiagnosticsController             │ │ │
│  │  └─────────────────────┘      └────────────────────────────────────┘ │ │
│  │                                          │                            │ │
│  │                                          ▼                            │ │
│  │                               ┌────────────────────────┐              │ │
│  │                               │  GooseExecutor         │              │ │
│  │                               │  (goose-cf-wrapper)    │              │ │
│  │                               │  - Session management  │              │ │
│  │                               │  - ProcessBuilder      │              │ │
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
└────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                        ┌─────────────────────────┐
                        │   LLM Provider API      │
                        │   (Anthropic, OpenAI,   │
                        │    Google, Databricks)  │
                        └─────────────────────────┘
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/auth/login` | POST | Form login with username + password (access code) |
| `/auth/status` | GET | Returns current authentication state and user info |
| `/auth/provider` | GET | Detects available SSO provider (used by login page) |
| `/logout` | POST | End the current session |
| `/api/chat/health` | GET | Check Goose availability and version |
| `/api/chat/sessions` | POST | Create a new conversation session |
| `/api/chat/sessions/{id}/messages` | POST | Send message (returns SSE stream) |
| `/api/chat/sessions/{id}/status` | GET | Check session status |
| `/api/chat/sessions/{id}` | DELETE | Close a session |
| `/api/diagnostics/env` | GET | View relevant environment variables |
| `/oauth/initiate/{serverName}` | POST | Initiate OAuth flow for an MCP server |
| `/oauth/callback` | GET | OAuth callback handler |
| `/oauth/status/{serverName}` | GET | Check OAuth authentication status |
| `/oauth/disconnect/{serverName}` | POST | Revoke OAuth tokens for an MCP server |
| `/oauth/client-metadata.json` | GET | Client ID Metadata Document for dynamic registration |

## Authentication

All requests require authentication. There are two login methods:

1. **Access code (always available)** — a shared secret configured via the `APP_AUTH_SECRET` environment variable. Users enter this code on the login page.
2. **SSO (auto-detected)** — when a Cloud Foundry SSO tile (`p-identity`) is bound to the app, a "Sign in with SSO" button appears on the login page automatically. No feature flag is needed.

### How it works

- Spring Security is configured with form login. An in-memory user (`user`) is created with the password set to `APP_AUTH_SECRET`.
- On page load, the login page fetches `/auth/provider` to check whether an OAuth2 client registration exists. If one is detected (via `java-cfenv-boot-pivotal-sso`), the SSO button is shown.
- Both login methods result in a valid Spring Security session and redirect to the app.

### Configuring the access code

Set the `APP_AUTH_SECRET` environment variable. Locally:

```bash
export APP_AUTH_SECRET=my-secret-code
./mvnw spring-boot:run
```

For Cloud Foundry, provide it in a vars file or via CredHub:

```yaml
# vars.yaml
APP_AUTH_SECRET: my-secret-code
```

```bash
cf push --vars-file vars.yaml
```

The default value is `changeme` if the variable is not set.

### Enabling SSO on Cloud Foundry

SSO activates automatically when a `p-identity` service instance is bound to the app. No application properties need to change.

```bash
# Create an SSO service instance (plan name may vary by foundation)
cf create-service p-identity <plan> my-sso

# Bind it to the app
cf bind-service goose-agent-chat my-sso

# Restage to pick up the new binding
cf restage goose-agent-chat
```

Once bound, the login page will show both the access code field and a "Sign in with SSO" button. The `java-cfenv-boot-pivotal-sso` library detects the binding and auto-configures Spring Security's OAuth2 client registration.

## Configuration

### Application Properties

| Property | Default | Description |
|----------|---------|-------------|
| `goose.enabled` | `true` | Enable/disable Goose integration |
| `app.auth.secret` | `changeme` | Shared access code for login (set via `APP_AUTH_SECRET` env var) |

### Environment Variables

| Variable | Description |
|----------|-------------|
| `APP_AUTH_SECRET` | Shared access code for login |
| `GOOSE_CLI_PATH` | Path to Goose CLI binary |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `OPENAI_API_KEY` | OpenAI API key |
| `GOOGLE_API_KEY` | Google AI API key |
| `DATABRICKS_HOST` | Databricks workspace URL |
| `DATABRICKS_TOKEN` | Databricks access token |
| `GOOSE_PROVIDER__TYPE` | Default provider (anthropic, openai, etc.) |
| `GOOSE_PROVIDER__MODEL` | Default model |

## License

MIT License

