# goose-agent-chat

Spring Boot 3.5 (Java 21) + Angular 21 fullstack app that provides a web chat UI for the [Goose](https://block.github.io/goose/) AI agent. Deployed as a single JAR to Cloud Foundry (Tanzu Platform).

## Commands

### Build (full — compiles Java + Angular into one JAR)
```bash
./mvnw package
```

### Development (two terminals)
```bash
# Terminal 1 — Spring Boot backend on :8080
./mvnw spring-boot:run

# Terminal 2 — Angular dev server on :4200 (proxies /api, /auth, /login, /logout, /oauth2 → :8080)
cd src/main/frontend && npm start
```

### Frontend only
```bash
cd src/main/frontend
npm run build   # production build
npm test        # unit tests (vitest)
```

### Deploy to CF
```bash
./mvnw package
cf push --vars-file vars.yaml
```

## Architecture

```
src/main/
├── java/org/tanzu/goosechat/   # All controllers in one flat package (no sub-packages)
├── frontend/                   # Angular 21 app (zoneless, signals)
│   └── src/app/
│       ├── components/         # chat, activity-panel, config-panel, todo-list, security-diagram-dialog
│       └── services/           # chat.service, auth.service, mcp-oauth.service
└── resources/
    ├── application.properties
    ├── .goose-config.yml        # Goose provider/model/extensions/MCP config
    └── static/theme.css         # Shared CSS theme (source of truth)
```

### Key Java controllers
| File | Role |
|------|------|
| `GooseChatController` | SSE streaming chat sessions via `GooseExecutor` |
| `GooseConfigController` | Exposes skills/MCP servers from `.goose-config.yml` to frontend |
| `BrokerStatusController` | Tells frontend whether Agent Credential Broker is configured |
| `AuthController` | OAuth2 user info endpoint |
| `SecurityConfig` | OAuth2 login via CF `agent-sso` service binding |

## Gotchas

### Theme sharing
`src/main/resources/static/theme.css` is the **source of truth** for the shared theme. The Maven `antrun` plugin copies it to `src/main/frontend/src/_theme.scss` during `generate-resources`. **Do not edit `_theme.scss` directly** — changes will be overwritten on the next build.

### Angular build is embedded in the JAR
The `frontend-maven-plugin` runs `npm ci` + `ng build` during `mvn package`. The Angular output (`dist/frontend/browser/`) is then copied into `static/` inside the JAR. There is no separate frontend deployment.

### Agent Credential Broker vs. direct OAuth
The app has two MCP credential acquisition modes:
- **Broker mode**: `BROKER_BASE_URL` env var is set → frontend links to the broker's grants UI
- **Direct OAuth mode**: no broker → inline OAuth popup flow in the frontend

`BrokerStatusController` (`/api/broker/status`) exposes which mode is active.

### Goose configuration
`src/main/resources/.goose-config.yml` configures the Goose provider, model, extensions, MCP servers, and skills. On CF, the `goose-buildpack` installs the Goose CLI binary at the version specified by `GOOSE_VERSION` in `manifest.yml`.

### CF secrets
`vars.yaml` holds all secrets (API keys, passwords) and is **not committed to git**. `manifest.yml` references them as `((VAR_NAME))` placeholders. Pass via `cf push --vars-file vars.yaml`.

## Environment Variables (CF)

| Variable | Purpose |
|----------|---------|
| `OPENAI_API_KEY` | LLM provider key (matches provider in `.goose-config.yml`) |
| `BROKER_BASE_URL` | Internal mTLS URL to Agent Credential Broker (C2C) |
| `BROKER_PUBLIC_URL` | Public browser-accessible URL for broker grants page |
| `GOOSE_VERSION` | Goose CLI version installed by buildpack |
| `TANZU_HUB_URL/USER/PASSWORD` | Tanzu Hub credentials injected into Goose sessions |
