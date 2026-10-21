# VS Code development launches

Open the repository root in VS Code, install the Extension Pack for Java, configure
JDK 25, and let the Maven projects finish importing. Install the matching Chromium
once with `bash scripts/local/install-rendering.sh`.

Select **PermaFormula Local** in Run and Debug and press F5. It starts the renderer,
then starts **PermaFormula API** when the renderer prints
`event=renderer_ready transport=tcp`. Both services use the repository root as their
working directory and the same native-access and environment settings as Eclipse.
The Debug Console shows service output. Stop both debug sessions when finished;
this launch does not adopt already running services. For individual services, select
**PermaFormula Renderer** or **PermaFormula API**. Use Run Without Debugging for an
individual service without the debugger; use F5 for the combined launch.

Supply any local credentials, such as `PERMAFORMULA_ADMIN_PASSWORD`, through the
environment inherited by VS Code. Keep credentials out of the tracked launch file.

For the UI, run `bash scripts/local/start-ui.sh`. To expose API and UI publicly, run
`bash scripts/local/start-tunnel.sh` first. The tunnel script updates both IDEs' API
launchers and `frontend/.env.local`, including when called by `start-local.sh` or
with `--configure-only`. Restart the API and Vite after an origin change. See the
[local development guide](../scripts/local/README.md) for options.

`launch.json` is shared and must remain valid JSON (without comments or trailing
commas), since the origin writer parses it. The writer preserves other launch
configurations, environment entries and settings. Review generated tunnel URL
changes before committing. Other personal `.vscode` settings remain ignored.

VS Code references: [Java debugging](https://code.visualstudio.com/docs/java/java-debugging)
and [launch configuration and server-ready actions](https://code.visualstudio.com/docs/debugtest/debugging-configuration).
