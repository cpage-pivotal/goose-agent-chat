---
name: chat-with-goose
description: >-
  Send messages to the Goose Agent Chat app and stream the response (including which
  MCP tools Goose invokes). Use this whenever the user wants to "chat with goose",
  "ask goose", "send a message to goose", drive/test the Goose Agent Chat app, or
  verify that an MCP flow (e.g. GitHub or tanzu-hub via the credential broker) works
  end to end. The app authenticates via Keycloak SSO (federated to Google/GitHub),
  so login happens once in a browser and this skill reuses that session.
---

# Chat with Goose Agent Chat

## Auth model (read this first)

The app (default `https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com`) logs users in
with **Keycloak SSO, federated to Google/GitHub**. There is **no headless username/
password login** — a federated user has no local password to POST, and Google's login
can't be scripted. So the model is:

1. **Log in once in a real browser** (interactive — the human completes the Google/GitHub step).
2. **Reuse that browser's session cookies** for scripted messaging.

Two cookies matter and the helper carries both: `JSESSIONID` (the server session) and
`__VCAP_ID__` (CF sticky-routing — it pins the app instance holding your session;
without it you can land on an instance that doesn't know you).

## Step 1 — Get an authenticated browser session

Use `playwright-cli`. Open the app headed and let the user finish the SSO login:

```bash
playwright-cli -s=goose open "https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com/" --headed
```

The app redirects to Keycloak; the user clicks Google (or GitHub) and completes it. If a
realm session already exists in that browser, it may sign in silently. Confirm success:

```bash
playwright-cli -s=goose eval "async()=>{const r=await fetch('/auth/status',{credentials:'include'});return (await r.json()).authenticated;}"
```

Reuse an existing logged-in `playwright-cli` session if you already have one — no need to
open a new browser.

## Step 2 — Send messages with the helper

`goose-chat-helper.sh` extracts the cookies from the browser session and drives the chat
(create/reuse session → stream → parse SSE). Pass the playwright session name:

```bash
./goose-chat-helper.sh --from-playwright goose "List the names of 3 of my GitHub repositories"
```

- The working cookie is cached (`/tmp/goose-chat-cookie.txt`, mode 600) and the chat
  session id in `/tmp/goose-chat-session.txt`, so **follow-up messages need no flags** and
  continue the same conversation:
  ```bash
  ./goose-chat-helper.sh "and how many open issues does the first one have?"
  ```
- Force a specific conversation with `--session chat-abc123` (the UI's copy-session-id
  button gives you the id). Start fresh with `rm /tmp/goose-chat-session.txt`.
- A different deployment: `--url https://my-goose.example.com` (cached after first use).
- Explicit cookies instead of a browser: `--cookie "JSESSIONID=...; __VCAP_ID__=..."`.

When the cached cookie stops authenticating (session timed out — the app expires idle
sessions after ~30 min, and every redeploy drops in-memory sessions), the helper says so
and clears the cache. Re-do Step 1 (the browser session usually just needs a fresh SSO
bounce) and re-run with `--from-playwright`.

## Reading the output

The helper prints, to **stderr** (so it doesn't pollute the response text on stdout):
- `✓ Authenticated as <email>` and the session id
- `[Tool Call: <extension>/<tool>]` for each MCP tool Goose invokes — use these to verify
  an agent actually exercised the expected flow (e.g. `github/get_me`, or a `tanzu-hub`
  tool). MCP servers start disabled and are enabled on demand, so expect an
  `extensionmanager/manage_extensions` call before the first real tool call.

Goose's text answer streams to **stdout**, ending with `✓ Complete (N tokens)`.

**Example** — `--from-playwright goose "show my GitHub username"` yields tool calls
`extensionmanager/manage_extensions` then `github/get_me`, and the response
"Your GitHub username is **cpage-pivotal**." A real GitHub tool call returning the correct
username confirms the whole broker credential path (Keycloak login → token exchange →
delegation → GitHub token) is working.

## If a fully non-interactive path is ever needed

It isn't possible for the current setup without app changes: the app is an OAuth2 **client**
(session-cookie auth), not a resource server, so a bearer token from a Keycloak
`client_credentials`/ROPC grant would not authenticate its `/api/chat` endpoints. Making
that work would require (a) a **local** (non-federated) Keycloak user with a password and a
client with direct-access grants, and (b) adding resource-server JWT auth to the app so it
accepts bearer tokens. Out of scope here — the browser-session approach above is the
supported path.

## Requirements

`playwright-cli`, `curl`, `jq`, `python3`.
