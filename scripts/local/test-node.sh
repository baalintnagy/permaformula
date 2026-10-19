#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname "$0")/common.sh"
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/bin" "$fixture/nvm" "$fixture/empty"
printf '#!/usr/bin/env bash\nexit 0\n' >"$fixture/bin/node"
chmod +x "$fixture/bin/node"
cat >"$fixture/nvm/nvm.sh" <<'EOF'
[[ $1 == --no-use ]]
nvm() {
    [[ $* == 'use --silent default' ]] || return 1
    export PATH="$NODE_FIXTURE/bin:$PATH"
}
EOF
export NODE_FIXTURE=$fixture
(
    export PATH="$fixture/bin" NVM_DIR="$fixture/missing" NVM_SYMLINK=
    ensure_node
    [[ $PATH == "$fixture/bin" ]]
)
(
    export PATH="$fixture/empty" NVM_DIR="$fixture/nvm" NVM_SYMLINK=
    ensure_node
    [[ $(command -v node) == "$fixture/bin/node" ]]
)
(
    export PATH="$fixture/empty" NVM_DIR="$fixture/missing" NVM_SYMLINK="$fixture/bin"
    ensure_node
    [[ $(command -v node) == "$fixture/bin/node" ]]
)
if (
    export PATH="$fixture/empty" NVM_DIR="$fixture/missing" NVM_SYMLINK=
    ensure_node
) >"$fixture/missing.log" 2>&1; then
    fail 'Missing Node was accepted.'
fi
grep -q 'Node is unavailable' "$fixture/missing.log"
echo 'PASS: existing Node, nvm default activation, nvm-windows symlink and missing Node.'
