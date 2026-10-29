#!/bin/bash
# Setup step "paseo" (architecture §2.5, §3.3, §5.3). Env: PASEO_DAEMON_VERSION (required).
# - Writes /root/.paseo/config.json only when it does not exist (user edits are kept).
# - Installs @getpaseo/cli@$PASEO_DAEMON_VERSION when the installed version differs.
# - Installs each agent CLI only when it is missing; the app never upgrades agents (§2.5).
# - Refreshes the global agent instructions.
set -euo pipefail
exec </dev/null
: "${PASEO_DAEMON_VERSION:?PASEO_DAEMON_VERSION is not set}"
export npm_config_update_notifier=false npm_config_fund=false npm_config_audit=false
here="${BASH_SOURCE[0]%/*}"
modules=/usr/local/lib/node_modules

command -v node >/dev/null || { echo "node is not installed in /usr/local" >&2; exit 1; }

config=/root/.paseo/config.json
if [ ! -e "$config" ]; then
  mkdir -p "${config%/*}"
  cat >"$config.tmp.$$" <<'JSON'
{
  "version": 1,
  "daemon": { "listen": "127.0.0.1:6767", "relay": { "enabled": false } },
  "features": { "dictation": { "enabled": false }, "voiceMode": { "enabled": false } },
  "agents": {
    "providers": {
      "copilot": { "enabled": false },
      "pi": { "enabled": false },
      "omp": { "enabled": false }
    }
  }
}
JSON
  mv -f "$config.tmp.$$" "$config"
fi

installed=$(node -p "require('$modules/@getpaseo/cli/package.json').version" 2>/dev/null || true)
want=()
[ "$installed" = "$PASEO_DAEMON_VERSION" ] || want+=("@getpaseo/cli@$PASEO_DAEMON_VERSION")
for p in @anthropic-ai/claude-code @openai/codex opencode-ai; do
  [ -f "$modules/$p/package.json" ] || want+=("$p")
done
if [ ${#want[@]} -gt 0 ]; then
  echo "npm install -g ${want[*]}"
  npm install -g "${want[@]}"
  npm cache clean --force # ~0.9 GB of tarballs nobody reuses on the phone
fi

bash "$here/agent-instructions.sh"
echo "paseo: ok ($(paseo --version 2>/dev/null || echo "$PASEO_DAEMON_VERSION"))"
