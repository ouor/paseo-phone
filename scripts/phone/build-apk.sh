#!/usr/bin/env bash
# Builds the Paseo Phone release APK (arm64-v8a) -> artifacts/paseo-phone-arm64.apk.
# Same order as scripts/build-apk.mjs, in bash because that script cannot spawn npm/gradlew on
# Windows. See docs/phone-dev-machine/architecture/architecture.md §9.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
app="$root/packages/app"

# Relative path: Windows node cannot resolve Git Bash /c/... paths.
daemon_version=$(cd "$root" && node -p "require('./packages/server/package.json').version")
if ! npm view "@getpaseo/cli@$daemon_version" version >/dev/null 2>&1; then
  echo "warning: @getpaseo/cli@$daemon_version is not on npm yet; the on-device 'paseo' setup step will fail" >&2
fi

dirty=""
[ -z "$(git -C "$root" status --porcelain)" ] || dirty="-dirty"
export APP_VARIANT=phone EXPO_PUBLIC_LOCAL_DAEMON=127.0.0.1:6767 PASEO_WATCHDOG_BUILD=1
export PASEO_DIAGNOSTIC_BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
export PASEO_DIAGNOSTIC_REVISION="$(git -C "$root" rev-parse --short HEAD)$dirty"

(cd "$root" && npm run build:app-deps)
# --clean: the variant changes the applicationId, so a stale android/ project must not be reused.
(cd "$app" && npx expo prebuild --platform android --no-install --clean)
# Gradle does not track Metro's environment flags; always regenerate the JS bundle.
rm -rf "$app/android/app/build/generated/assets"
(cd "$app/android" && ./gradlew assembleRelease --no-daemon --max-workers=2 \
  -Dorg.gradle.parallel=false -PreactNativeArchitectures=arm64-v8a)

mkdir -p "$root/artifacts"
cp "$app/android/app/build/outputs/apk/release/app-release.apk" "$root/artifacts/paseo-phone-arm64.apk"
echo "Paseo Phone APK: $root/artifacts/paseo-phone-arm64.apk"
