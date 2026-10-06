#!/bin/sh
set -eu
umask 077
mkdir -p "$HOME"
exec java --enable-native-access=ALL-UNNAMED -Xmx128m -cp '/app/service.jar:/app/lib/*' org.permmath.renderer.RendererServiceMain "$@"
