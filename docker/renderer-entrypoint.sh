#!/bin/sh
set -eu
umask 077
mkdir -p "$HOME"
# CVE-2026-114938: retain until the pinned Temurin image includes 25.0.5.1+2 or later.
exec java -XX:-UseBASE64Intrinsics --enable-native-access=ALL-UNNAMED -Xmx256m -cp '/app/service.jar:/app/lib/*' org.permaformula.renderer.RendererServiceMain "$@"
