#!/bin/sh
# OpenCode — open-source terminal coding agent (npm distribution).
set -e
if command -v opencode >/dev/null 2>&1; then
	echo "already installed: $(opencode --version 2>&1 | head -1)"
	exit 0
fi
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq nodejs npm git ca-certificates
# npm's exit handler wedges under proot on the postinstall step, and npm
# skips lifecycle scripts as (fake) root anyway — the package still
# unpacks, so run its postinstall explicitly. It fetches the real binary.
npm install -g --no-fund --no-audit opencode-ai || true
cd "$(npm root -g)/opencode-ai" && node postinstall.mjs
opencode --version
