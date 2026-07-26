#!/bin/sh
# Claude Code CLI — the reference agent harness for Cellar machines.
# Auth at runtime: cellar exec <m> -e ANTHROPIC_API_KEY=... -- claude -p "..."
set -e
# Idempotent: npm's reinstall-over-existing rename dance breaks under
# proot (ENOTEMPTY); skip when present — claude updates itself anyway.
if command -v claude >/dev/null 2>&1; then
	echo "already installed: $(claude --version)"
	exit 0
fi
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq nodejs npm git ca-certificates >/dev/null
echo "installing Claude Code (~250 MB with node)"
npm install -g --no-fund --no-audit --loglevel=error @anthropic-ai/claude-code >/tmp/cc-npm.log 2>&1 || true
# npm skips lifecycle scripts when running as (fake) root; the launcher
# is set up by the package's postinstall, so run it explicitly.
cd "$(npm root -g)/@anthropic-ai/claude-code" 2>/dev/null || {
	echo "npm did not unpack the package. last lines of its log:"
	tail -15 /tmp/cc-npm.log
	exit 1
}
node install.cjs >/dev/null 2>&1 || true
claude --version
