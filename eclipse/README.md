# Eclipse development launches

Java formatting settings are shared in `java-style.properties`: four spaces, a
100-character target width, member annotations on separate lines, and boolean
operators at the start of continuation lines. The properties file can also be
passed to Eclipse's command-line formatter with `-config`.

Use braces for all conditional and loop bodies. Keep production local types
explicit, except in loop declarations. Put each Java stream stage on a separate
line and prefer collection operations when a loop needs no extra mutable state.
Use the shared formatter and the repository's applicable contributor instructions.

These shared configs were copied from the existing workspace launches. They retain
the renderer sandbox setting, Java native-access arguments, Spring Boot settings,
and the group's wait for `event=renderer_ready transport=tcp` before starting Java API.
Working directories now use `${workspace_loc:permaformula-api}/..` (the repository root).

Import the Maven projects, including the root project, and refresh them. The `.launch`
files should appear in Run Configurations. If they do not, use **File > Import >
Run/Debug > Launch Configurations**, selecting this directory. Use **PermaFormula Local**
to start both services, or **PermaFormula Renderer** and **PermaFormula API** separately.
The old workspace configs prefixed with `!` are independent and are not updated.
If imported configs become workspace-local copies, select the repository file under
the launch configuration's **Common > Save as > Shared file** setting.

## Public development URLs

From the repository root, run:

```bash
bash scripts/local/start-tunnel.sh
```

This starts two Cloudflare Quick Tunnels: API at `http://localhost:8086` and UI at
`http://127.0.0.1:5173`. It prints local API/UI addresses before launching, waits for both
connections, prints the public URLs, updates `PERMAFORMULA_HOST` and `PERMAFORMULA_IMAGE_HOST` in **PermaFormula API**,
and updates `PERMAFORMULA_DEV_API` and `PERMAFORMULA_DEV_UI_ORIGIN` in `frontend/.env.local`,
preserving other entries. Vite allows the exact UI hostname and uses its HTTPS
WebSocket endpoint for hot reload. Open the printed **UI tunnel URL** to use the builder.
The renderer needs no public URL; the group refers to the updated API config.
Refresh Eclipse (F5), restart the API, then start/restart `scripts/local/start-ui.sh`.
Keep the tunnel terminal running; Ctrl+C stops both cloudflared processes. Java and Vite
continue running. Each new Quick Tunnel gets a new hostname; old copied links expire.

For an already configured, locally managed named tunnel:

```bash
bash scripts/local/start-tunnel.sh --tunnel-name permaformula --public-origin https://math.example.com
# Named API and UI tunnels (each already configured):
bash scripts/local/start-tunnel.sh --tunnel-name permaformula --public-origin https://math.example.com --ui-tunnel-name permaformula-ui --ui-public-origin https://builder.example.com
# Optional non-default cloudflared config:
bash scripts/local/start-tunnel.sh --tunnel-name permaformula --public-origin https://math.example.com --config-path C:/path/config.yml
```

Its existing Cloudflare route must point to `http://localhost:8086`; the script does
not create DNS records or change Cloudflare account configuration. `--local-origin`
controls the API Quick Tunnel only. `--ui-local-origin http://127.0.0.1:5174` selects
a different local UI port (also pass `--port 5174` to `start-ui.sh`). `--ui-config-path`
selects a custom config for a named UI tunnel. A named API tunnel still starts a
Quick Tunnel for the UI unless `--ui-tunnel-name` is provided. Use `--no-ui` for an API-only
session; this clears the UI origin from `.env.local`.
If tunnels are already running as services, update
the files without starting another process:

```bash
bash scripts/local/start-tunnel.sh --configure-only --public-origin https://math.example.com --ui-public-origin https://builder.example.com
```

Requires `cloudflared` on PATH (or supply `--cloudflared C:/path/cloudflared.exe`).
Quick Tunnel startup may require temporarily moving an existing default cloudflared
config aside; inspect `.runtime/tunnel/<session>/*.log` if startup fails.
The frontend's development setting controls both copied URLs and its proxy target,
so browser API requests also pass through the tunnel. An `--api-origin` argument or
`PERMAFORMULA_DEV_API` environment variable overrides `.env.local`; omit those overrides.
The script changes the tracked API launch file; review those changes before committing.
`.env.local` and tunnel logs are ignored by Git. Stopping a tunnel leaves the last
URL in the settings; rerun the script to replace it.

Cloudflare references: [Quick Tunnels](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)
and [named tunnels](https://developers.cloudflare.com/tunnel/features/locally-managed-tunnels/create-local-tunnel/).
