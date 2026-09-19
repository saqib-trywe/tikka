#!/bin/sh
# Installs tikka from a published release: the CLI onto your PATH, the daemon jar where the CLI looks for it.
#
# Usage:  curl -fsSL .../get-tikka.sh | sh
#         TIKKA_VERSION=v0.2.0 BIN_DIR=/usr/local/bin sh get-tikka.sh
#
# Deliberately POSIX sh with no dependencies beyond curl, tar and a hashing tool, because it runs on a machine that
# has nothing of tikka's yet. It never writes outside BIN_DIR and TIKKA_HOME.
set -eu

repo="saqib-trywe/tikka"
bin="${BIN_DIR:-$HOME/.local/bin}"
home="${TIKKA_HOME:-$HOME/.tikka}"
version="${TIKKA_VERSION:-latest}"

case "$(uname -s)/$(uname -m)" in
  Darwin/arm64) build=tikka-macos-arm64 ;;
  Linux/x86_64) build=tikka-linux-x86_64 ;;
  Darwin/x86_64)
    echo "No build for Intel Macs. Build from source instead: https://github.com/$repo#building" >&2
    exit 1
    ;;
  *)
    echo "No build for $(uname -s) on $(uname -m); macOS on Apple silicon and Linux on x86-64 are what exist." >&2
    echo "Build from source instead: https://github.com/$repo#building" >&2
    exit 1
    ;;
esac

# TIKKA_BASE_URL points the script at a directory of the same files, so the install can be exercised before a
# release exists. Everything else is identical, which is the point of testing it at all.
if [ -n "${TIKKA_BASE_URL:-}" ]; then
  base="$TIKKA_BASE_URL"
elif [ "$version" = latest ]; then
  base="https://github.com/$repo/releases/latest/download"
else
  base="https://github.com/$repo/releases/download/$version"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "downloading $build from $version"
curl -fsSL "$base/$build.tar.gz" -o "$work/$build.tar.gz"
curl -fsSL "$base/tikka-daemon.jar" -o "$work/tikka-daemon.jar"
curl -fsSL "$base/SHA256SUMS" -o "$work/SHA256SUMS"

# What was downloaded has to be what was published; a release that cannot be checked is not worth installing.
if command -v sha256sum >/dev/null; then
  checker="sha256sum"
elif command -v shasum >/dev/null; then
  checker="shasum -a 256"
else
  echo "no sha256sum or shasum to check the download with" >&2
  exit 1
fi
# Both lines have to be found before anything is checked: a pattern that matches nothing would otherwise hand the
# checker an empty list, which it would happily report as success.
grep -E "[ *]($build\.tar\.gz|tikka-daemon\.jar)\$" "$work/SHA256SUMS" >"$work/wanted" || true
if [ "$(wc -l <"$work/wanted" | tr -d ' ')" != "2" ]; then
  echo "SHA256SUMS does not name both downloads; stopping" >&2
  exit 1
fi
(cd "$work" && $checker -c wanted) || {
  echo "the download does not match SHA256SUMS; stopping" >&2
  exit 1
}

tar -xzf "$work/$build.tar.gz" -C "$work"

mkdir -p "$bin" "$home/lib" "$home/logs"
# Copy beside the destination and rename over it: a running daemon goes on reading the jar it already opened, and a
# running command goes on being the binary it already was.
cp -R "$work/$build/." "$home/lib/cli.new"
rm -rf "$home/lib/cli"
mv "$home/lib/cli.new" "$home/lib/cli"
ln -sf "$home/lib/cli/tikka" "$bin/.tikka.new"
mv -f "$bin/.tikka.new" "$bin/tikka"
cp "$work/tikka-daemon.jar" "$home/lib/.tikka-daemon.jar.new"
mv -f "$home/lib/.tikka-daemon.jar.new" "$home/lib/tikka-daemon.jar"

echo "installed $bin/tikka -> $home/lib/cli/tikka, and $home/lib/tikka-daemon.jar"
case ":$PATH:" in
  *":$bin:"*) ;;
  *) echo "note: $bin is not on your PATH" ;;
esac
"$bin/tikka" --version
echo "next: tikka daemon install (first time), or tikka daemon restart (after an upgrade)"
