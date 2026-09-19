#!/usr/bin/env bash
# Makes a macOS build of the CLI self-contained, so it runs on a Mac that has never seen Homebrew.
#
# The binary links libidn2 by absolute path, libidn2 links libunistring and libintl the same way, and none of those
# exist on a machine without Homebrew — or on an Intel Mac, where the prefix is /usr/local rather than /opt/homebrew.
# So the whole closure is copied next to the binary and every reference to it rewritten to @loader_path. What is left
# pointing outside the directory is /usr/lib and /System, which every Mac has.
#
# libidn2 and its dependencies are LGPL. They travel as the shared libraries they are, replaceable by anyone who
# wants a different build, which is what that licence asks for.
#
# Usage: scripts/bundle-macos.sh <binary> <staging-directory>
set -euo pipefail

binary="$1"
staging="$2"

mkdir -p "$staging"
cp "$binary" "$staging/tikka"
chmod u+w "$staging/tikka"

# Anything outside /usr/lib and /System has to travel with us: Homebrew's prefix varies by machine and architecture.
external() {
  otool -L "$1" | tail -n +2 | awk '{print $1}' | grep -Ev '^(/usr/lib|/System)' || true
}

# Everything bundled here is LGPL, which asks that its terms travel with it.
keep_licence() {
  case "$1" in
    */opt/*/lib/*)
      # The last /opt/ is Homebrew's formula directory; the first is the prefix itself on /opt/homebrew.
      formula="${1##*/opt/}"
      formula="${formula%%/*}"
      real="$(readlink -f "${1%%/lib/*}" 2>/dev/null || echo "${1%%/lib/*}")"
      mkdir -p "$staging/licences/$formula"
      find "$real" -maxdepth 1 \( -name 'COPYING*' -o -name 'LICENSE*' \) -exec cp {} "$staging/licences/$formula/" \; 2>/dev/null || true
      ;;
  esac
}

# Breadth-first over the closure: each library can name others, and those have to come too.
pending=("$staging/tikka")
copied=()
while [ ${#pending[@]} -gt 0 ]; do
  current="${pending[0]}"
  pending=("${pending[@]:1}")
  for dependency in $(external "$current"); do
    name="$(basename "$dependency")"
    # A library names itself first; that is an id, not a dependency.
    [ "$name" = "$(basename "$current")" ] && continue
    if [ ! -f "$staging/$name" ]; then
      cp "$dependency" "$staging/$name"
      chmod u+w "$staging/$name"
      keep_licence "$dependency"
      copied+=("$name")
      pending+=("$staging/$name")
    fi
  done
done

# Every reference, in the binary and in each copied library, now points beside whatever is doing the loading.
for file in "$staging/tikka" "${copied[@]/#/$staging/}"; do
  [ -f "$file" ] || continue
  for dependency in $(external "$file"); do
    install_name_tool -change "$dependency" "@loader_path/$(basename "$dependency")" "$file" 2>/dev/null || true
  done
  install_name_tool -id "@loader_path/$(basename "$file")" "$file" 2>/dev/null || true
  # Editing a Mach-O invalidates its signature, and macOS refuses to run an arm64 binary whose signature does not
  # match. Ad-hoc is all we can offer without a Developer ID, and it is enough for a download made by curl.
  codesign --force --sign - "$file"
done

remaining="$(external "$staging/tikka" || true)"
for name in "${copied[@]}"; do remaining="$remaining $(external "$staging/$name" || true)"; done
if grep -Eq '/(opt|usr)/local|/opt/homebrew' <<<"$remaining"; then
  echo "still pointing at a prefix that will not exist elsewhere:"
  echo "$remaining"
  exit 1
fi

"$staging/tikka" --version
echo "bundled $(ls "$staging" | wc -l | tr -d ' ') files into $staging"
