---
name: github
description: Handles questions about GitHub repositories, issues, and pull requests.
---

The `github` MCP server starts disabled so its tools don't consume context until a request actually needs them.

1. Call `manage_extensions` with `action: "enable"` and `extension_name: "github"`.
2. Its tools then appear as normal callable functions (repos, issues, pull requests, etc). Call the one that matches the request directly.
3. Leave it enabled afterward; there's no need to disable it again for the rest of the session.
