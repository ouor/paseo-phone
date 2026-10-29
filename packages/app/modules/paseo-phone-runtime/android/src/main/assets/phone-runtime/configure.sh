#!/bin/bash
# Setup step "configure" (architecture §2.5). Prepares the freshly extracted rootfs for apt.
# Host keys come from the openssh-server postinst (packages.sh); phone.conf from run-sshd.sh.
# Idempotent: every file is rewritten whole. Env: PASEO_PHONE_NODENAME (optional, /etc/hosts entry).
set -euo pipefail
exec </dev/null
write() { # write <path> <mode>: atomic replace from stdin
  mkdir -p "${1%/*}"
  cat >"$1.tmp.$$"
  chmod "$2" "$1.tmp.$$"
  mv -f "$1.tmp.$$" "$1"
}

# proot has no Android resolver; the tarball may ship resolv.conf as a symlink.
rm -f /etc/resolv.conf
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' | write /etc/resolv.conf 0644

{
  printf '127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n'
  if [ -n "${PASEO_PHONE_NODENAME:-}" ]; then printf '127.0.1.1 %s\n' "$PASEO_PHONE_NODENAME"; fi
} | write /etc/hosts 0644

# apt's _apt sandbox user cannot drop privileges under proot (Kai).
echo 'APT::Sandbox::User "root";' | write /etc/apt/apt.conf.d/99paseo-phone 0644
# fsync is expensive through proot.
echo 'force-unsafe-io' | write /etc/dpkg/dpkg.cfg.d/99paseo-phone 0644
# No init system: keep package postinst scripts from starting daemons (sshd) during install.
printf '#!/bin/sh\nexit 101\n' | write /usr/sbin/policy-rc.d 0755

mkdir -p /root/.ssh
chmod 700 /root/.ssh
echo "configure: ok"
