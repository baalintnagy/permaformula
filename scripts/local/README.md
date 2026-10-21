# Local development

Bash supports Linux and Git Bash on Windows; it owns and stops only the
children it starts. Linux uses process groups with a bounded TERM/KILL shutdown;
Git Bash uses Windows process-tree termination. Logs are under `.runtime/local`.
Startup reports six stages with elapsed time, Maven module progress, readiness
confirmations, and log locations. Long builds and readiness waits report every
ten seconds; `--skip-build` and `--no-tunnels` are shown explicitly.

Requirements: JDK 25, Maven, Node/npm, matching Playwright Chromium installed by
`install-rendering`, and curl. Cloudflared is needed when using tunnels.

```bash
bash scripts/local/install-rendering.sh
bash scripts/local/start-local.sh --admin-password 'your-admin-password' --no-tunnels
bash scripts/local/stop-local.sh
```

Omit `--no-tunnels` to launch the API and UI Cloudflare tunnels alongside the
Java services and Vite. Add `--skip-build` to reuse existing artifacts, or
`--ui-port 5174` to change the UI port. Named tunnel options include,
for example `--tunnel-name permaformula --public-origin https://api.example.org`.
Use `--help` on the shell entry points for their complete options.

The launcher and Chromium installer build Java modules under each module's
`target/local` directory, isolated from Eclipse's `target/classes` output.
After upgrading from the shared output layout, run once without `--skip-build`.

The default local API is on port 8086; the renderer listens on 9090. These
development services do not have the container deployment's isolation. Pass the admin password with the mandatory `--admin-password` parameter. It is passed to the API through its process environment.

For Eclipse- or VS Code-owned Java processes, start only Vite:

```bash
bash scripts/local/start-ui.sh --api-origin http://localhost:8086
```

`start-tunnel.sh --configure-only --public-origin https://api.example.org
--ui-public-origin https://ui.example.org` updates the Eclipse and VS Code API launches and
ignored `frontend/.env.local` configuration without launching tunnels. Refresh
Eclipse and restart Java/Vite after changing these settings. VS Code uses
[shared launches](../../.vscode/README.md) in `.vscode/launch.json`; both real tunnel
startup and the tunnel step in `start-local.sh` use the same configuration writer.

If a machine crash leaves `.runtime/local/shell.lock`, inspect running services
before removing that stale lock; the launcher never takes over another session.
