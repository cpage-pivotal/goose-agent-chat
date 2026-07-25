# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# goose-agent-chat

Spring Boot 4 (Java 21) + Angular 21 fullstack app that provides a web chat UI for the [Goose](https://block.github.io/goose/) AI agent. Deployed as a single JAR to Cloud Foundry (Tanzu Platform).

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
npm test        # unit tests (ng test)
```

There are no backend tests (`src/test/java` is empty); `./mvnw package` builds only.

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
│       ├── components/         # chat, activity-panel, config-panel, login, todo-list, security-diagram-dialog
│       └── services/           # chat.service, auth.service, mcp-oauth.service
└── resources/
    ├── application.properties
    ├── application-oauth.properties  # Keycloak OIDC client (only under the "oauth" profile)
    ├── .goose-config.yml        # Goose provider/model/extensions/MCP config
    ├── .goose/skills/           # Skills bundled into the JAR (github, tanzu-hub)
    └── static/theme.css         # Shared CSS theme (source of truth)
```

The Goose CLI integration itself lives in the external `org.tanzu.goose:goose-cf-wrapper`
dependency (provides `GooseExecutor`, CF env plumbing, and java-cfenv-boot-tanzu-genai
transitively) — it is not in this repo.

### Key Java controllers
| File | Role |
|------|------|
| `GooseChatController` | SSE streaming chat sessions via `GooseExecutor` |
| `GooseConfigController` | Exposes skills/MCP servers from `.goose-config.yml` to frontend |
| `BrokerStatusController` | Tells frontend whether Agent Credential Broker is configured |
| `AuthController` | OAuth2 user info endpoint |
| `AuthModeProvider` | Picks OAUTH2 vs PASSWORD mode at startup (see below) |
| `SecurityConfig` | Wires the filter chain for the selected auth mode |
| `LoginPageController` / `ChatHealthController` / `DiagnosticsController` | Login page routing, health, debug endpoints |

## Gotchas

### Theme sharing
`src/main/resources/static/theme.css` is the **source of truth** for the shared theme. The Maven `antrun` plugin copies it to `src/main/frontend/src/_theme.scss` during `generate-resources`. **Do not edit `_theme.scss` directly** — changes will be overwritten on the next build.

### Angular build is embedded in the JAR
The `frontend-maven-plugin` runs `npm ci` + `ng build` during `mvn package`. The Angular output (`dist/frontend/browser/`) is then copied into `static/` inside the JAR. There is no separate frontend deployment.

### Two auth modes, selected by profile
Login is standard OIDC, **not** the old CF `agent-sso`/UAA binding. Nothing in the code is
provider-specific (plain `.oauth2Login()`, issuer-uri discovery, no role mapping, no
RP-initiated logout) — Keycloak is simply what's deployed behind it, and any OIDC provider
works by swapping `OIDC_ISSUER_URI`/`CLIENT_ID`/`CLIENT_SECRET`. The only per-provider
setting is `user-name-attribute=preferred_username`. The client registration lives in
`application-oauth.properties` and is only loaded under the `oauth` Spring profile (`SPRING_PROFILES_ACTIVE=oauth` in `manifest.yml`). Gating it behind a
profile is deliberate: with no profile there is no `ClientRegistrationRepository` bean, so
`AuthModeProvider` falls back to PASSWORD mode (shared secret `app.auth.secret` /
`APP_AUTH_SECRET`) and OAuth2 property validation never runs. Setting `BROKER_BASE_URL`
without SSO is a **fail-fast startup error** — the broker requires OAUTH2 mode.

Locally, `./mvnw spring-boot:run` therefore starts in PASSWORD mode unless you activate
the profile and supply `OIDC_ISSUER_URI`/`OIDC_CLIENT_ID`/`OIDC_CLIENT_SECRET`.

### Agent Credential Broker vs. direct OAuth
The app has two MCP credential acquisition modes:
- **Broker mode**: `BROKER_BASE_URL` env var is set → frontend links to the broker's grants UI
- **Direct OAuth mode**: no broker → inline OAuth popup flow in the frontend

`BrokerStatusController` (`/api/broker/status`) exposes which mode is active.

### Goose configuration
`src/main/resources/.goose-config.yml` configures the Goose provider, model, extensions, MCP servers, and skills. On CF, the `goose-buildpack` installs the Goose CLI binary at the version specified by `GOOSE_VERSION` in `manifest.yml`.

The `github` and `tanzu-hub` MCP servers are declared with `enabled: false` **on purpose** —
keeping their tool schemas out of the model's context. Each has a matching skill under
`.goose/skills/` that instructs the model to call `manage_extensions` to turn the server on
only when a request needs it. Adding an MCP server without a paired skill means it will
never be enabled at runtime. Both use `brokerAuth: true` (credentials via the broker).

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
| `SPRING_PROFILES_ACTIVE=oauth` | Enables OIDC login (otherwise PASSWORD mode) |
| `OIDC_ISSUER_URI/CLIENT_ID/CLIENT_SECRET` | OIDC client config (Keycloak in practice) |
| `APP_AUTH_SECRET` | Shared password for the PASSWORD-mode fallback |
| `GITHUB_PAT` | Auth for the private `goose-buildpack` URL in `manifest.yml` |
