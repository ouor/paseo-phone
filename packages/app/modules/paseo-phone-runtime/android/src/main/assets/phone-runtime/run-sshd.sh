#!/bin/bash
# Long-running: OpenSSH sshd in the foreground, key login only (architecture §5.1, phone.conf).
# Env: PASEO_PHONE_SSH_PORT (default 2222).
set -eu
exec </dev/null
mkdir -p /run/sshd # privilege separation dir; /run is not persistent across rootfs boots
ssh-keygen -A >/dev/null # no-op when the host keys exist
exec /usr/sbin/sshd -D -e -p "${PASEO_PHONE_SSH_PORT:-2222}"
