#!/bin/bash
# Run inside proot Debian (proot-distro login debian). Installs base tools and Node 22 LTS.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates git openssh-server build-essential python3 procps xz-utils ripgrep unzip >/dev/null
NODE_VER=v22.20.0
if ! node -v 2>/dev/null | grep -q "$NODE_VER"; then
  curl -fsSL "https://nodejs.org/dist/$NODE_VER/node-$NODE_VER-linux-arm64.tar.xz" | tar -xJ -C /usr/local --strip-components=1
fi
node -v; npm -v; git --version
