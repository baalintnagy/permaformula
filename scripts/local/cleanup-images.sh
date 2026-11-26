#!/usr/bin/env bash
# Remove old local PermaFormula tags, keeping the newest image in each repository.
set -Eeuo pipefail
dry_run=false
for argument in "$@"; do
    case $argument in
        --dry-run) dry_run=true ;;
        --help)
            cat <<'EOF'
cleanup-images.sh [--dry-run]

Remove older permaformula/* image tags from the current Docker daemon.
Keep the newest image in each repository and every image referenced by any
container (including stopped containers). --dry-run only lists removals.
Uses the current Docker context / DOCKER_HOST; select your local engine first.
Does not prune dangling images, build cache, containers, networks or volumes.
EOF
            exit 0
            ;;
        *) echo "Unknown argument: $argument" >&2; exit 1 ;;
    esac
done

command -v docker >/dev/null || { echo 'Docker is required.' >&2; exit 1; }
# Capture command results directly so daemon/inspection failures stop cleanup.
listing=$(docker image ls --no-trunc --format '{{.Repository}} {{.Tag}} {{.ID}}')
containers=$(docker container ls --all --quiet --no-trunc)
declare -A protected=() newest=()
while IFS= read -r container; do
    [[ -n $container ]] || continue
    image=$(docker container inspect --format '{{.Image}}' "$container")
    protected["$image"]=1
done <<<"$containers"

# Docker lists newest-created images first. Keep all tags of that image ID.
while read -r repository tag image; do
    [[ $repository == permaformula/* && $tag != '<none>' ]] || continue
    if [[ -z ${newest[$repository]:-} ]]; then
        newest["$repository"]=$image
        protected["$image"]=1
    fi
done <<<"$listing"

count=0
failures=0
while read -r repository tag image; do
    [[ $repository == permaformula/* && $tag != '<none>' ]] || continue
    [[ -z ${protected[$image]:-} ]] || continue
    reference="$repository:$tag"
    if [[ $dry_run == true ]]; then
        printf 'Would remove: %s\n' "$reference"
    else
        # Never force removal; Docker protects containers created after the snapshot.
        if ! docker image rm "$reference"; then
            failures=$((failures + 1))
        fi
    fi
    count=$((count + 1))
done <<<"$listing"
printf 'Cleanup candidates: %s; removal failures: %s.\n' "$count" "$failures"
((failures == 0))
