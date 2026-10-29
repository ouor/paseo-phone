#!/data/data/com.termux/files/usr/bin/bash
# Run in Termux (not inside proot). Starts the Paseo daemon in proot Debian, detached from the ssh session.
# proot-distro uses --kill-on-exit, so the daemon must be the foreground child of its own proot session.
termux-wake-lock 2>/dev/null || true
setsid nohup proot-distro login debian -- bash -lc \
  'export PASEO_LISTEN=0.0.0.0:6767; exec paseo daemon run' \
  > "$HOME/paseo-daemon.out" 2>&1 < /dev/null &
echo "started pid $!"
