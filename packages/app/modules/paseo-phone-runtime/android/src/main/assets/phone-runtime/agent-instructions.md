<!-- paseo-phone:begin (managed by Paseo Phone; edits inside this block are overwritten) -->

## This machine

- You run inside Debian (proot) on an Android phone. There is no systemd, no Docker, no root kernel access.
- Keep repositories and dependencies under /root. Never work inside ~/exchange.
- ~/exchange is shared with Android apps. Use it only to receive or hand over files. It does not support symlinks or executable bits.
- File-heavy operations are slow here. Avoid repo-wide scans of node_modules and full rebuilds when a targeted command works.
- Codex sandboxing is unavailable on this machine.
<!-- paseo-phone:end -->
