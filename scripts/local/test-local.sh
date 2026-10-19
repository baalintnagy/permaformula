#!/usr/bin/env bash
# Native CLI/configuration fixtures; no Java servers or real tunnels are started.
set -Eeuo pipefail
directory=$(cd "$(dirname "$0")" && pwd)
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/scripts/local" "$fixture/frontend" "$fixture/eclipse" "$fixture/bin" "$fixture/.runtime/local/shell.lock"
cp "$directory/"*.sh "$directory/configure-origins.cjs" "$fixture/scripts/local/"
cat >"$fixture/eclipse/PermaFormula API.launch" <<'EOF'
<launchConfiguration><mapAttribute key="org.eclipse.debug.core.environmentVariables"><mapEntry key="OTHER" value="retained"/><mapEntry key="PERMAFORMULA_HOST" value="old"/></mapAttribute></launchConfiguration>
EOF
printf 'OTHER=retained\n' >"$fixture/frontend/.env.local"
cat >"$fixture/bin/npm" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$LOCAL_FIXTURE/npm.calls"
EOF
chmod +x "$fixture/bin/npm"
export PATH="$fixture/bin:$PATH" LOCAL_FIXTURE="$fixture"
bash "$fixture/scripts/local/start-ui.sh" --api-origin http://localhost:8090 --port 5174
grep -q '^ci$' "$fixture/npm.calls"
grep -q '^run dev -- --port 5174 --strictPort$' "$fixture/npm.calls"
bash "$fixture/scripts/local/start-tunnel.sh" --configure-only --public-origin https://api.example.org --ui-public-origin https://ui.example.org
grep -q 'OTHER.*retained' "$fixture/eclipse/PermaFormula API.launch"
grep -q 'PERMAFORMULA_HOST.*https://api.example.org' "$fixture/eclipse/PermaFormula API.launch"
grep -q '^PERMAFORMULA_DEV_API=https://api.example.org$' "$fixture/frontend/.env.local"
grep -q '^PERMAFORMULA_DEV_UI_ORIGIN=https://ui.example.org$' "$fixture/frontend/.env.local"
bash "$fixture/scripts/local/stop-local.sh"
[[ -f $fixture/.runtime/local/stop-shell ]]
for name in start-local start-tunnel start-ui install-rendering stop-local; do
    bash "$directory/$name.sh" --help >/dev/null
done
if bash "$directory/start-local.sh" --no-tunnels </dev/null >/dev/null 2>&1; then
    echo 'Missing admin parameter accepted.' >&2
    exit 1
fi
source "$fixture/scripts/local/common.sh"
spawn "$fixture/exited-child.log" bash -c 'exit 0'
wait "$spawned_pid"
stop_children
children=()
spawn "$fixture/child.log" bash -c 'sleep 60'
child=$spawned_pid
stop_children
if kill -0 "$child" 2>/dev/null; then
    echo 'Owned child survived local cleanup.' >&2
    exit 1
fi
timeout=1
wait_ready Fixture true >"$fixture/ready-progress.log"
grep -q 'Waiting for Fixture' "$fixture/ready-progress.log"
grep -q 'Fixture ready' "$fixture/ready-progress.log"
# A failing build must report its stage/log location and still clear its lock.
rmdir "$fixture/.runtime/local/shell.lock"
cat >"$fixture/bin/node" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat >"$fixture/bin/mvn" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$LOCAL_FIXTURE/mvn.calls"
echo '[INFO] Building fixture [1/1]'
exit 1
EOF
chmod +x "$fixture/bin/node" "$fixture/bin/mvn"
if bash "$fixture/scripts/local/start-local.sh" --admin-password fixture --no-tunnels >"$fixture/start-progress.log" 2>&1; then
    echo 'Failed fixture build accepted.' >&2
    exit 1
fi
grep -q '\[1/6\] Checking local ports' "$fixture/start-progress.log"
grep -q '\[2/6\] Building Java modules' "$fixture/start-progress.log"
grep -q 'Build failed. See .*build.log' "$fixture/start-progress.log"
grep -q 'Local session stopped' "$fixture/start-progress.log"
grep -qx -- '-Dpermaformula.build.directory=target/local' "$fixture/mvn.calls"
grep -qx -- '-DoutputDirectory=target/local/runtime-deps' "$fixture/mvn.calls"
grep -qx -- '-Dmdep.overWriteReleases=true' "$fixture/mvn.calls"
[[ ! -d $fixture/.runtime/local/shell.lock ]]
echo 'PASS: Bash local CLI, frontend launch arguments, tunnel origin configuration preservation and shutdown request.'
