#!/bin/bash
# Long-running: the Paseo daemon as the foreground process of its proot session (architecture §3.2).
# `daemon start` would detach and die with proot --kill-on-exit. PASEO_HOME defaults to /root/.paseo.
set -eu
exec </dev/null
cd /root
exec paseo daemon run
