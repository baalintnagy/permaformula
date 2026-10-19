#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
port=5173
origin=
while (($#)); do
    case $1 in
        --port)
            port=${2:?}
            shift 2
            ;;
        --api-origin)
            origin=${2:?}
            shift 2
            ;;
        --help)
            echo 'start-ui.sh [--port PORT] [--api-origin URL]'
            exit 0
            ;;
        *) fail "Unknown option: $1" ;;
    esac
done
valid_port "$port"
if [[ -n $origin ]]; then
    valid_origin "$origin"
    export PERMAFORMULA_DEV_API=$origin
fi
cd "$local_root/frontend"
ensure_node
[[ -d node_modules ]] || npm ci
echo "PermaFormula UI: http://127.0.0.1:$port"
exec npm run dev -- --port "$port" --strictPort
