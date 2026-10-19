#!/bin/sh
set -eu
umask 022
mkdir -p /status
while :; do
    state=ok
    # The database volume and host root can be separate filesystems.
    # df reports free blocks without reading database contents.
    db=$(df -Pk /database | awk 'NR==2 {print $4}')
    host=$(df -Pk /host-storage | awk 'NR==2 {print $4}')
    inodes=$(df -Pi /database | awk 'NR==2 {print $4}')
    host_inodes=$(df -Pi /host-storage | awk 'NR==2 {print $4}')
    if [ "${db:-0}" -lt 10485760 ] || [ "${host:-0}" -lt 8388608 ] \
        || [ "${inodes:-0}" -lt 10000 ] || [ "${host_inodes:-0}" -lt 10000 ]; then
        state=pressure
        echo "event=storage_pressure database_free_kib=$db host_free_kib=$host database_free_inodes=$inodes host_free_inodes=$host_inodes" >&2
    fi
    printf '%s %s\n' "$state" "$(date +%s)" >/status/pressure.new
    mv /status/pressure.new /status/pressure
    [ "${1:-}" != --once ] || exit 0
    sleep 30
done
