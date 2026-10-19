#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
admin_password=
skip_build=false
ui_port=5173
timeout=180
tunnels=true
tunnel_arguments=()
while (($#)); do
    case $1 in
        --admin-password)
            admin_password=${2:?}
            shift 2
            ;;
        --skip-build)
            skip_build=true
            shift
            ;;
        --no-tunnels)
            tunnels=false
            shift
            ;;
        --ui-port)
            ui_port=${2:?}
            shift 2
            ;;
        --startup-timeout-seconds)
            timeout=${2:?}
            shift 2
            ;;
        --cloudflared | --tunnel-name | --public-origin | --config-path | --ui-tunnel-name | --ui-public-origin | --ui-config-path)
            tunnel_arguments+=("$1" "${2:?}")
            shift 2
            ;;
        --help)
            echo 'start-local.sh --admin-password PASSWORD [--skip-build] [--no-tunnels] [--ui-port PORT] [--startup-timeout-seconds N] [tunnel options]'
            exit 0
            ;;
        *) fail "Unknown option: $1" ;;
    esac
done
[[ -n ${admin_password//[[:space:]]/} && ! $admin_password =~ [[:cntrl:]] ]] || fail '--admin-password is mandatory and must be nonblank without control characters.'
export PERMAFORMULA_ADMIN_PASSWORD=$admin_password
valid_port "$ui_port"
[[ $timeout =~ ^[0-9]+$ ]] && ((timeout >= 1 && timeout <= 600)) || fail 'Invalid timeout.'
[[ -z ${PERMAFORMULA_ADMIN_PASSWORD:-} ]] || (($(printf '%s' "$PERMAFORMULA_ADMIN_PASSWORD" | wc -c) <= 72)) || fail 'Admin password exceeds 72 UTF-8 bytes.'
cd "$local_root"
ensure_node
runtime="$local_root/.runtime/local"
mkdir -p "$runtime"
lock="$runtime/shell.lock"
mkdir "$lock" 2>/dev/null || fail 'A shell local session already exists. Use stop-local.sh; inspect a stale lock before removal.'
stop_file="$runtime/stop-shell"
rm -f "$stop_file"
cleanup() {
    local status=$?
    trap - EXIT
    trap '' INT TERM
    # Cleanup is best effort: a child that has already exited must not leave
    # the session lock behind or change the launcher's original exit status.
    set +e
    progress 'Stopping local services...'
    stop_children
    rm -f "$stop_file"
    rmdir "$lock"
    progress 'Local session stopped.'
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
progress "[1/6] Checking local ports: renderer 9090, API 8086, UI $ui_port..."
node -e 'const net=require("net"); Promise.all(process.argv.slice(1).map(port=>new Promise(resolve=>{const socket=net.connect(+port,"127.0.0.1");socket.setTimeout(250);socket.on("connect",()=>{console.error("Port occupied: "+port);socket.destroy();resolve(true)});socket.on("error",()=>resolve(false));socket.on("timeout",()=>{socket.destroy();resolve(false)});}))).then(results=>process.exit(results.some(Boolean)?1:0));' 9090 8086 "$ui_port"
run="$runtime/$(date +%s)-$$"
mkdir -p "$run"
progress "Session logs: $run"
if [[ $skip_build == false ]]; then
    progress "[2/6] Building Java modules and collecting runtime dependencies. Log: $run/build.log"
    spawn "$run/build.log" mvn package dependency:copy-dependencies -DskipTests -Dpermaformula.build.directory=target/local -DincludeScope=runtime -DoutputDirectory=target/local/runtime-deps -Dmdep.overWriteReleases=true
    build_started=$SECONDS
    next_update=$((SECONDS + 10))
    build_module=
    while kill -0 "$spawned_pid" 2>/dev/null; do
        [[ ! -e $stop_file ]] || fail 'Build stopped.'
        current_module=
        if [[ -f $run/build.log ]]; then
            current_module=$(tail -100 "$run/build.log" | sed -n 's/^\[INFO\] Building /Building /p' | tail -1)
        fi
        if [[ -n $current_module && $current_module != "$build_module" ]]; then
            progress "$current_module"
            build_module=$current_module
        fi
        if ((SECONDS >= next_update)); then
            progress "Build still running ($((SECONDS - build_started))s elapsed). Log: $run/build.log"
            next_update=$((SECONDS + 10))
        fi
        sleep 0.5
    done
    wait "$spawned_pid" || fail "Build failed. See $run/build.log"
    children=()
    progress "Build completed ($((SECONDS - build_started))s)."
else
    progress '[2/6] Build skipped; checking existing artifacts.'
fi
for artifact in renderer-service/target/local/renderer-service-1.0.jar renderer-service/target/local/runtime-deps permaformula-api/target/local/permaformula-api-1.0.jar; do
    [[ -e $artifact ]] || fail "Missing $artifact; run without --skip-build."
done
api_origin=http://127.0.0.1:8086
ui_origin="http://127.0.0.1:$ui_port"
if [[ $tunnels == true ]]; then
    progress "[3/6] Starting API and UI tunnels. Log: $run/tunnels.log"
    ready="$run/tunnels.json"
    spawn "$run/tunnels.log" bash "$local_scripts/start-tunnel.sh" --ready-file "$ready" --ui-local-origin "$ui_origin" --startup-timeout-seconds "$timeout" "${tunnel_arguments[@]}"
    wait_ready Tunnels test -f "$ready"
    api_origin=$(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1])).ApiOrigin)' "$(native_path "$ready")")
    ui_origin=$(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1])).UiOrigin)' "$(native_path "$ready")")
else
    progress '[3/6] Tunnels disabled; using local origins.'
fi
separator=:
[[ ${OSTYPE:-} != msys* && ${OSTYPE:-} != cygwin* ]] || separator=';'
java_command=java
if [[ -n ${JAVA_HOME:-} ]]; then
    java_home=$JAVA_HOME
    command -v cygpath >/dev/null && java_home=$(cygpath -u "$JAVA_HOME")
    java_command="$java_home/bin/java"
fi
progress "[4/6] Starting renderer. Log: $run/renderer.log"
spawn "$run/renderer.log" env RENDERER_CHROMIUM_SANDBOX=false "$java_command" --enable-native-access=ALL-UNNAMED \
    -cp "renderer-service/target/local/renderer-service-1.0.jar${separator}renderer-service/target/local/runtime-deps/*" org.permaformula.renderer.RendererServiceMain
wait_ready Renderer bash -c 'grep -q "event=renderer_ready" "$1"' _ "$run/renderer.log"
progress "[5/6] Starting API. Log: $run/api.log"
spawn "$run/api.log" env PERMAFORMULA_RENDERING_PLAINTEXT=true PERMAFORMULA_HOST="$api_origin" PERMAFORMULA_IMAGE_HOST="$api_origin" \
    "$java_command" --enable-native-access=ALL-UNNAMED -jar permaformula-api/target/local/permaformula-api-1.0.jar
wait_ready API curl --silent --fail --max-time 2 http://127.0.0.1:8086/v3/api-docs -o /dev/null
progress "[6/6] Starting UI development server. Log: $run/ui.log"
spawn "$run/ui.log" env PERMAFORMULA_DEV_UI_ORIGIN="$ui_origin" bash "$local_scripts/start-ui.sh" --port "$ui_port" --api-origin "$api_origin"
wait_ready UI curl --silent --fail --max-time 2 "http://127.0.0.1:$ui_port/" -o /dev/null
progress 'Local session ready.'
echo "Local UI: http://127.0.0.1:$ui_port"
echo "Public UI: $ui_origin"
echo "API: $api_origin"
echo 'Ctrl+C or scripts/local/stop-local.sh stops this session.'
while [[ ! -e $stop_file ]]; do
    for pid in "${children[@]}"; do
        kill -0 "$pid" 2>/dev/null || fail "A component exited. See $run"
    done
    sleep 0.5
done
