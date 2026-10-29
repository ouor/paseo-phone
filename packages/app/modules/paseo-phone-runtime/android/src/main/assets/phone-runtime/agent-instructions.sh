#!/bin/bash
# Writes the managed block from agent-instructions.md into the three agents' global instruction
# files (architecture §5.3). Content outside the block is kept; the block goes at the end.
# Idempotent: a second run leaves the files byte-identical.
set -euo pipefail
exec </dev/null
block="${BASH_SOURCE[0]%/*}/agent-instructions.md"

for f in /root/.claude/CLAUDE.md /root/.codex/AGENTS.md /root/.config/opencode/AGENTS.md; do
  mkdir -p "${f%/*}"
  tmp="$f.tmp.$$"
  {
    if [ -f "$f" ]; then
      # Drop the old block and trailing blank lines, then separate user content with one blank line.
      awk '/^<!-- paseo-phone:begin/{skip=1} !skip{lines[++n]=$0} /^<!-- paseo-phone:end/{skip=0}
        END{while(n>0 && lines[n] ~ /^[[:space:]]*$/) n--; for(i=1;i<=n;i++) print lines[i]; if(n) print ""}' "$f"
    fi
    cat "$block"
  } >"$tmp"
  mv -f "$tmp" "$f"
done
echo "agent-instructions: ok"
