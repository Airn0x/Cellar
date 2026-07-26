#!/bin/sh
# OpenCode — open-source terminal coding agent (npm distribution).
set -e
if command -v opencode >/dev/null 2>&1; then
	echo "already installed: $(opencode --version 2>&1 | head -1)"
	exit 0
fi
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq nodejs npm git ca-certificates >/dev/null
# npm's exit handler wedges under proot on the postinstall step, and npm
# skips lifecycle scripts as (fake) root anyway — the package still
# unpacks, so run its postinstall explicitly. It fetches the real binary.
echo "installing opencode (fetches a ~180 MB binary — give it a few minutes)"
# npm's exit handler misbehaves under proot even on success (field note 5),
# so its noise goes to a log and only a real failure is reported.
npm install -g --no-fund --no-audit --loglevel=error opencode-ai >/tmp/opencode-npm.log 2>&1 || true
cd "$(npm root -g)/opencode-ai" 2>/dev/null || {
	echo "npm did not unpack the package. last lines of its log:"
	tail -15 /tmp/opencode-npm.log
	exit 1
}
node postinstall.mjs >/dev/null 2>&1 || {
	echo "the opencode binary download failed — check the network and retry"
	exit 1
}
opencode --version
