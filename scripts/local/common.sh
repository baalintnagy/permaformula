#!/usr/bin/env bash
set -Eeuo pipefail
local_scripts=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
local_root=$(cd "$local_scripts/../.." && pwd)
native_path() {
    if command -v cygpath >/dev/null; then
        cygpath -w "$1"
    else
        printf '%s\n' "$1"
    fi
}
fail() {
    echo "$*" >&2
    exit 1
}
ensure_node() {
    if command -v node >/dev/null; then
        return
    fi
    if [[ -n ${NVM_SYMLINK:-} ]]; then
        local node_directory=$NVM_SYMLINK
        if command -v cygpath >/dev/null; then
            node_directory=$(cygpath -u "$node_directory")
        fi
        export PATH="$node_directory:$PATH"
    fi
    if command -v node >/dev/null; then
        return
    fi
    local nvm_directory=${NVM_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/nvm}
    if [[ -z ${NVM_DIR:-} && -s $HOME/.nvm/nvm.sh ]]; then
        nvm_directory=$HOME/.nvm
    fi
    if [[ -s $nvm_directory/nvm.sh ]]; then
        source "$nvm_directory/nvm.sh" --no-use
        nvm use --silent default || fail 'Cannot activate Node with nvm. Run nvm alias default VERSION.'
    fi
    command -v node >/dev/null || fail 'Node is unavailable. Activate Node with nvm and ensure it is on PATH.'
}
valid_origin() {
    [[ $1 =~ ^https?://[a-zA-Z0-9.-]+(:[0-9]+)?$ ]] || fail 'Expected an HTTP(S) origin without a path.'
}
valid_port() {
    [[ $1 =~ ^[0-9]+$ ]] && (($1 >= 1024 && $1 <= 65535)) || fail 'Invalid port.'
}
children=()
progress() {
    printf '[%ss] %s\n' "$SECONDS" "$*"
}
spawn() {
    local log=$1
    shift
    if command -v setsid >/dev/null; then
        setsid "$@" >"$log" 2>&1 &
    else
        "$@" >"$log" 2>&1 &
    fi
    spawned_pid=$!
    children+=("$spawned_pid")
}
stop_children() {
    local pid windows_pid
    for pid in "${children[@]}"; do
        if [[ ${OSTYPE:-} == msys* || ${OSTYPE:-} == cygwin* ]]; then
            # Ctrl+C may already have stopped this child. With errexit/pipefail,
            # a missing PID must not abort cleanup before the lock is removed.
            windows_pid=$(ps -p "$pid" 2>/dev/null | awk 'NR>1 {print $4}') || windows_pid=
            if [[ -n $windows_pid ]]; then
                MSYS_NO_PATHCONV=1 taskkill.exe /PID "$windows_pid" /T /F >/dev/null 2>&1 || true
            fi
        else
            kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
        fi
    done
    if [[ ${OSTYPE:-} != msys* && ${OSTYPE:-} != cygwin* ]]; then
        local deadline=$((SECONDS + 15)) running
        while ((SECONDS < deadline)); do
            running=false
            for pid in "${children[@]}"; do
                kill -0 "$pid" 2>/dev/null && running=true
            done
            [[ $running == true ]] || break
            sleep 0.2
        done
        for pid in "${children[@]}"; do
            if kill -0 "$pid" 2>/dev/null; then
                kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
            fi
        done
    fi
    for pid in "${children[@]}"; do
        wait "$pid" 2>/dev/null || true
    done
}
wait_ready() {
    local description=$1
    shift
    local started=$SECONDS deadline=$((SECONDS + timeout)) next_update=$((SECONDS + 10))
    progress "Waiting for $description (timeout ${timeout}s)..."
    until "$@"; do
        [[ ! -e ${stop_file:-/nonexistent} ]] || fail 'Local shutdown requested.'
        for pid in "${children[@]}"; do
            kill -0 "$pid" 2>/dev/null || fail "$description child stopped; inspect logs."
        done
        ((SECONDS < deadline)) || fail "$description startup timed out."
        if ((SECONDS >= next_update)); then
            progress "$description still starting ($((SECONDS - started))s elapsed)..."
            next_update=$((SECONDS + 10))
        fi
        sleep 0.5
    done
    progress "$description ready ($((SECONDS - started))s)."
}
