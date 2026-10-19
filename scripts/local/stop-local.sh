#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
[[ ${1:-} != --help ]] || {
    echo 'stop-local.sh: request shutdown of the shell local session'
    exit 0
}
if [[ -d $local_root/.runtime/local/shell.lock ]]; then
    touch "$local_root/.runtime/local/stop-shell"
    echo 'Local shell shutdown requested.'
elif [[ -f $local_root/.runtime/local/session.lock ]]; then
    fail 'A legacy local session exists. Stop its original launcher with Ctrl+C before starting a Bash session.'
else
    echo 'No local session is running.'
fi
