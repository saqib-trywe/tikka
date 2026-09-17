#!/usr/bin/env bash
# Builds tikka and installs it: the release CLI into a bin directory, and the daemon jar into the home.
# Usage: scripts/install.sh [bin-dir]
#   bin-dir defaults to ~/.local/bin; the home is $TIKKA_HOME, or ~/.tikka.
# Then run `tikka daemon install` once, or `tikka daemon restart` after an upgrade.
set -euo pipefail

bin="${1:-$HOME/.local/bin}"
home="${TIKKA_HOME:-$HOME/.tikka}"

sbt "cliRelease; daemon/assembly"
binary="$(find target/out -path '*native*' -type f -name tikka | head -1)"

mkdir -p "$bin" "$home/lib" "$home/logs"
# Copy beside the destination, then rename over it: a running daemon keeps reading the jar it opened.
cp "$binary" "$bin/.tikka.new" && mv -f "$bin/.tikka.new" "$bin/tikka"
cp target/dist/tikka-daemon.jar "$home/lib/.tikka-daemon.jar.new" && mv -f "$home/lib/.tikka-daemon.jar.new" "$home/lib/tikka-daemon.jar"

echo "installed $bin/tikka and $home/lib/tikka-daemon.jar"
case ":$PATH:" in
  *":$bin:"*) ;;
  *) echo "note: $bin is not on your PATH" ;;
esac
echo "next: tikka daemon install (first time), or tikka daemon restart (after an upgrade)"
