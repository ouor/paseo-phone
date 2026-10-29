#!/bin/bash
# Long-running: OpenSSH sshd in the foreground, key login only (architecture §5.1).
# Env: PASEO_PHONE_SSH_PORT (required). phone.conf is rewritten from the asset on every start so
# edits made inside the guest do not survive a restart.
set -eu
exec </dev/null
: "${PASEO_PHONE_SSH_PORT:?PASEO_PHONE_SSH_PORT is not set}"
conf=/etc/ssh/sshd_config.d/phone.conf
mkdir -p "${conf%/*}" /run/sshd # /run/sshd: privilege separation dir
cp "${BASH_SOURCE[0]%/*}/sshd_config" "$conf.tmp.$$"
chmod 0644 "$conf.tmp.$$"
mv -f "$conf.tmp.$$" "$conf"
ssh-keygen -A >/dev/null # creates only missing host keys; restored identity keys are kept
exec /usr/sbin/sshd -D -e -p "$PASEO_PHONE_SSH_PORT"
