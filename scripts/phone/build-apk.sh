#!/usr/bin/env bash
# One-command Paseo Phone release build (arm64-v8a) -> artifacts/paseo-phone-arm64.apk.
# Same order as scripts/build-apk.mjs, in bash because that script cannot spawn npm/gradlew on
# Windows. See docs/phone-dev-machine/architecture/architecture.md §9 and core/core-rootfs.md.
#
# Usage: scripts/phone/build-apk.sh [--skip-tests] [--install] [--device <adb serial>]
#   --skip-tests  skip :paseo-phone-runtime:testReleaseUnitTest
#   --install     adb install -r the APK (default device devphone01:5555)
# Prints one line per step; the full output goes to artifacts/paseo-phone-build.log.
# Refuses a dirty tree (untracked external/ is allowed) and fails if the tree changes mid-build,
# so the APK always matches the commit written to artifacts/paseo-phone-arm64.apk.txt.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
app="$root/packages/app"
out="$root/artifacts"
apk="$out/paseo-phone-arm64.apk"
log="$out/paseo-phone-build.log"
tests=1 install=0 device="devphone01:5555"

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-tests) tests=0 ;;
    --install) install=1 ;;
    --device) device="${2:?--device needs a serial}"; shift ;;
    -h | --help) sed -n '2,11p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

mkdir -p "$out"
: >"$log"
started=$(date +%s)

# step <name> <timeout> <cmd...>: run with output to the log; on failure name the step and exit.
step() {
  local name=$1 limit=$2 t0 rc
  shift 2
  t0=$(date +%s)
  printf '%-12s ... ' "$name"
  printf '\n===== %s (%s) =====\n' "$name" "$*" >>"$log"
  timeout --kill-after=30s "$limit" "$@" >>"$log" 2>&1
  rc=$?
  if [ $rc -ne 0 ]; then
    [ $rc -eq 124 ] && echo "TIMEOUT after $limit" || echo "FAILED (exit $rc)"
    echo "--- last lines of $log:"
    tail -n 25 "$log"
    echo "BUILD FAILED at step: $name"
    exit 1
  fi
  echo "ok ($(($(date +%s) - t0))s)"
}

dirty() { git -C "$root" status --porcelain --untracked-files=all | grep -v '^?? external/'; }

# --- preflight ---------------------------------------------------------------------------------
if [ -n "$(dirty)" ]; then
  echo "Refusing to build: the working tree has changes (commit or stash them first):" >&2
  dirty >&2
  exit 1
fi
commit=$(git -C "$root" rev-parse HEAD)
short=$(git -C "$root" rev-parse --short HEAD)
echo "Building Paseo Phone from $short (log: $log)"
# A failed build must not leave an older APK that looks like this commit's.
rm -f "$apk" "$apk.txt"

# Relative path: Windows node cannot resolve Git Bash /c/... paths.
daemon_version=$(cd "$root" && node -p "require('./packages/server/package.json').version")
if ! timeout 60 npm view "@getpaseo/cli@$daemon_version" version >/dev/null 2>&1; then
  echo "warning: @getpaseo/cli@$daemon_version is not on npm (or npm is unreachable); the on-device 'paseo' setup step will fail"
fi

# npm 11 skips install scripts not on its allow list, which leaves react-native-enriched-markdown
# without its vendored tree-sitter sources and breaks the native build. Its postinstall needs
# bsdtar on Windows (Git Bash tar cannot read the archives).
repair_markdown() {
  local pkg="$app/node_modules/react-native-enriched-markdown"
  [ -d "$pkg" ] || pkg="$root/node_modules/react-native-enriched-markdown"
  [ -d "$pkg" ] || { echo "react-native-enriched-markdown is not installed; run npm install" >&2; return 1; }
  local vendor="$pkg/cpp/highlight/vendor"
  # generated/ is written later by the native build, so only check what the postinstall provides.
  if [ -d "$vendor/tree-sitter" ] && [ -n "$(ls -A "$vendor/grammars" 2>/dev/null)" ]; then
    echo "vendored grammars present"
    return 0
  fi
  local winbin=""
  [ -x /c/Windows/System32/tar.exe ] && winbin="/c/Windows/System32:"
  (cd "$pkg" && PATH="$winbin$PATH" INIT_CWD="$root" node postinstall.mjs) || return 1
  [ -d "$vendor/tree-sitter" ] && [ -n "$(ls -A "$vendor/grammars" 2>/dev/null)" ] || {
    echo "postinstall ran but the vendored grammars are still missing" >&2
    return 1
  }
}
export -f repair_markdown
export root app
step env-repair 10m bash -c repair_markdown

# --- build -------------------------------------------------------------------------------------
export APP_VARIANT=phone PASEO_WATCHDOG_BUILD=1
# phone-fork: EXPO_PUBLIC_LOCAL_DAEMON must match PhoneConstants DAEMON_HOST/DAEMON_PORT (architecture §7.5).
export EXPO_PUBLIC_LOCAL_DAEMON=127.0.0.1:6767
export PASEO_DIAGNOSTIC_BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
export PASEO_DIAGNOSTIC_REVISION="$short"

step app-deps 20m bash -c "cd '$root' && npm run build:app-deps"
# --clean: the variant changes the applicationId, so a stale android/ project must not be reused.
step prebuild 15m bash -c "cd '$app' && npx expo prebuild --platform android --no-install --clean"
# Gradle does not track Metro's environment flags; always regenerate the JS bundle.
rm -rf "$app/android/app/build/generated/assets"
gradle_tasks=(assembleRelease)
# Module unit tests run in the same Gradle call; a failing test means no APK (architecture §9).
[ $tests -eq 1 ] && gradle_tasks=(:paseo-phone-runtime:testReleaseUnitTest assembleRelease)
step gradle 60m bash -c "cd '$app/android' && ./gradlew ${gradle_tasks[*]} --no-daemon --max-workers=2 \
  -Dorg.gradle.parallel=false -PreactNativeArchitectures=arm64-v8a"

if [ -n "$(dirty)" ] || [ "$(git -C "$root" rev-parse HEAD)" != "$commit" ]; then
  echo "BUILD FAILED at step: verify (the tree changed during the build; the APK would not match $short)"
  exit 1
fi
cp "$app/android/app/build/outputs/apk/release/app-release.apk" "$apk"
size=$(wc -c <"$apk" | tr -d ' ')
sha=$(sha256sum "$apk" | cut -d' ' -f1)
{
  echo "commit=$commit"
  echo "built_at=$PASEO_DIAGNOSTIC_BUILD_TIME"
  echo "daemon_version=$daemon_version"
  echo "unit_tests=$([ $tests -eq 1 ] && echo passed || echo skipped)"
  echo "size_bytes=$size"
  echo "sha256=$sha"
} >"$apk.txt"

if [ $install -eq 1 ]; then
  step install 5m adb -s "$device" install -r "$apk"
fi

echo "BUILD OK in $(($(date +%s) - started))s"
echo "APK:    $apk ($((size / 1024 / 1024)) MB)"
echo "commit: $short   tests: $([ $tests -eq 1 ] && echo passed || echo skipped)"
echo "info:   $apk.txt"
