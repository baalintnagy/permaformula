#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
api=http://localhost:8086
ui=http://127.0.0.1:5173
api_name=
ui_name=
api_origin=
ui_origin=
api_config=
ui_config=
no_ui=false
configure_only=false
ready_file=
timeout=90
cloudflared=cloudflared
while (($#)); do
    case $1 in
        --local-origin)
            api=${2:?}
            shift 2
            ;;
        --ui-local-origin)
            ui=${2:?}
            shift 2
            ;;
        --tunnel-name)
            api_name=${2:?}
            shift 2
            ;;
        --ui-tunnel-name)
            ui_name=${2:?}
            shift 2
            ;;
        --public-origin)
            api_origin=${2:?}
            shift 2
            ;;
        --ui-public-origin)
            ui_origin=${2:?}
            shift 2
            ;;
        --config-path)
            api_config=${2:?}
            shift 2
            ;;
        --ui-config-path)
            ui_config=${2:?}
            shift 2
            ;;
        --cloudflared)
            cloudflared=${2:?}
            shift 2
            ;;
        --ready-file)
            ready_file=${2:?}
            shift 2
            ;;
        --startup-timeout-seconds)
            timeout=${2:?}
            shift 2
            ;;
        --no-ui)
            no_ui=true
            shift
            ;;
        --configure-only)
            configure_only=true
            shift
            ;;
        --help)
            echo 'start-tunnel.sh [--local-origin URL] [--ui-local-origin URL] [--tunnel-name NAME --public-origin HTTPS_URL] [--ui-tunnel-name NAME --ui-public-origin HTTPS_URL] [--config-path FILE] [--ui-config-path FILE] [--no-ui] [--configure-only] [--ready-file FILE] [--cloudflared COMMAND] [--startup-timeout-seconds N]'
            exit 0
            ;;
        *) fail "Unknown option: $1" ;;
    esac
done
valid_origin "$api"
valid_origin "$ui"
for origin in "$api_origin" "$ui_origin"; do
    [[ -z $origin ]] || {
        valid_origin "$origin"
        [[ $origin == https:* ]] || fail 'Public tunnel origins require HTTPS.'
    }
done
[[ $timeout =~ ^[0-9]+$ ]] && ((timeout >= 1 && timeout <= 600)) || fail 'Invalid timeout.'
[[ $no_ui == false || -z $ui_name$ui_origin$ui_config ]] || fail '--no-ui cannot include UI tunnel settings.'
[[ -z $api_name || -n $api_origin ]] || fail 'Named API tunnel requires --public-origin.'
[[ -z $ui_name || -n $ui_origin ]] || fail 'Named UI tunnel requires --ui-public-origin.'
ensure_node
configure() {
    local ready=$ready_file
    [[ -z $ready ]] || ready=$(native_path "$ready")
    node "$(native_path "$local_scripts/configure-origins.cjs")" "$api_origin" "$ui_origin" "$ready"
    echo "Public API: $api_origin"
    [[ -z $ui_origin ]] || echo "Public UI: $ui_origin"
}
if [[ $configure_only == true ]]; then
    [[ -n $api_origin && -z $api_name$ui_name$api_config$ui_config ]] || fail 'Configure-only requires an API origin and cannot start named tunnels.'
    configure
    exit 0
fi
[[ -n $api_name || -z $api_origin$api_config ]] || fail 'Quick API tunnels discover their own URLs.'
[[ -n $ui_name || -z $ui_origin$ui_config ]] || fail 'Quick UI tunnels discover their own URLs.'
for name in "$api_name" "$ui_name"; do
    [[ -z $name || $name =~ ^[a-zA-Z0-9_-]+$ ]] || fail 'Invalid tunnel name.'
done
run="$local_root/.runtime/tunnel/$(date +%s)-$$"
mkdir -p "$run"
trap stop_children EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
launch_tunnel() {
    local label=$1 name=$2 origin=$3 config=$4
    local arguments=(tunnel --no-autoupdate)
    [[ -z $config ]] || {
        [[ -f $config ]] || fail "Missing tunnel configuration: $config"
        arguments+=(--config "$(native_path "$config")")
    }
    if [[ -n $name ]]; then
        arguments+=(run "$name")
    else
        arguments+=(--url "$origin")
    fi
    spawn "$run/$label.log" "$cloudflared" "${arguments[@]}"
}
echo "Local API: $api"
if [[ $no_ui == false ]]; then
    echo "Local UI: $ui"
fi
launch_tunnel api "$api_name" "$api" "$api_config"
[[ $no_ui == true ]] || launch_tunnel ui "$ui_name" "$ui" "$ui_config"
deadline=$((SECONDS + timeout))
while true; do
    for pid in "${children[@]}"; do
        kill -0 "$pid" 2>/dev/null || fail "Cloudflared exited. See $run"
    done
    [[ -n $api_name ]] || api_origin=$(grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' "$run/api.log" | head -1 || true)
    [[ $no_ui == true || -n $ui_name ]] || ui_origin=$(grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' "$run/ui.log" | head -1 || true)
    if [[ -n $api_origin ]] && grep -q 'Registered tunnel connection' "$run/api.log"; then
        if [[ $no_ui == true ]] || {
            [[ -n $ui_origin ]] && grep -q 'Registered tunnel connection' "$run/ui.log"
        }; then
            break
        fi
    fi
    ((SECONDS < deadline)) || fail "Tunnel startup timed out. See $run"
    sleep 0.5
done
configure
echo "Tunnels running; Ctrl+C stops them. Logs: $run"
while true; do
    for pid in "${children[@]}"; do
        kill -0 "$pid" 2>/dev/null || fail 'Cloudflared stopped.'
    done
    sleep 0.5
done
