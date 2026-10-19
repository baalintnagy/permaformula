#!/bin/sh
set -eu
umask 077
mkdir -p "$HOME"
exec java --enable-native-access=ALL-UNNAMED -Xmx256m -cp '/app/service.jar:/app/lib/*' org.permaformula.renderer.RendererServiceMain "$@"
