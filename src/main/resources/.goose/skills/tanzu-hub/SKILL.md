---
name: tanzu-hub
description: Handles questions about Tanzu Platform foundations, organizations, spaces, routes, platform alerts, platform usage, platform vulnerabilities, marketplace, services, and Tanzu Hub health.
---

The `tanzu-hub` MCP server starts disabled so its tools don't consume context until a request actually needs them.

1. Call `manage_extensions` with `action: "enable"` and `extension_name: "tanzu-hub"`.
2. Its tools then appear as normal callable functions (e.g. listing foundations, organizations, spaces, routes, platform alerts/usage/vulnerabilities, marketplace, services, health). Call the one that matches the request directly — do not use shell commands or a CLI for this.
3. Leave it enabled afterward; there's no need to disable it again for the rest of the session.
