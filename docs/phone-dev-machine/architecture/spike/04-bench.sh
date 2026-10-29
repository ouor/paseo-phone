#!/usr/bin/env bash
# File-heavy benchmark. Usage: bash 04-bench.sh <workdir> <label>
# Same script runs inside proot Debian and in Termux native for comparison.
set -u
W=$1; L=$2
rm -rf "$W"; mkdir -p "$W"; cd "$W"
t() { local s=$(date +%s.%N); "$@" >/dev/null 2>&1; local rc=$?; local e=$(date +%s.%N); printf "%-14s %-32s %7.2fs rc=%d\n" "$L" "$NAME" "$(echo "$e - $s" | bc)" "$rc"; }
NAME="spawn true x200"; t bash -c 'T=$(type -P true); for i in $(seq 200); do "$T"; done'
NAME="git clone express (full)"; t git clone -q https://github.com/expressjs/express.git
cd express
NAME="npm cache clean"; t npm cache clean --force
NAME="npm install (cold cache)"; t npm install --no-audit --no-fund --ignore-scripts
NAME="files in node_modules"; echo "$L files: $(find node_modules -type f | wc -l)"
NAME="find node_modules -type f"; t find node_modules -type f
NAME="rg 'function' ."; t rg -c function .
NAME="git status (x5)"; t bash -c 'for i in 1 2 3 4 5; do git status >/dev/null; done'
NAME="git log --stat -500"; t git log --stat -500
NAME="tar node_modules"; t tar -cf ../nm.tar node_modules
NAME="rm -rf node_modules"; t rm -rf node_modules
NAME="npm install (warm cache)"; t npm install --no-audit --no-fund --ignore-scripts --prefer-offline
NAME="npm test (mocha)"; t npm test
