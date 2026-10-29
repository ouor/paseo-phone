#!/bin/bash
# Setup step "packages" (architecture §2.5). Installs the base Debian tools.
# Progress contract: prints "PASEO_PHONE_TOTAL=<n>" once (packages apt will set up), then apt's
# "Setting up ..." lines stream through unchanged. Idempotent: apt skips what is installed.
set -euo pipefail
exec </dev/null
export DEBIAN_FRONTEND=noninteractive

pkgs=(ca-certificates curl git openssh-server build-essential python3 procps xz-utils ripgrep unzip less nano)
apt_opts=(-y -q --no-install-recommends -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)

apt-get update -q
total=$(apt-get -s "${apt_opts[@]}" install "${pkgs[@]}" | grep -c '^Inst ' || true)
echo "PASEO_PHONE_TOTAL=$total"
apt-get "${apt_opts[@]}" install "${pkgs[@]}"
apt-get clean
echo "packages: ok"
