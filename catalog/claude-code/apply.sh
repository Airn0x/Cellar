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
apt-get update -qq
apt-get install -y -qq nodejs npm git ca-certificates
npm install -g --no-fund --no-audit @anthropic-ai/claude-code
# npm skips lifecycle scripts when running as (fake) root; the launcher
# is set up by the package's postinstall, so run it explicitly.
cd "$(npm root -g)/@anthropic-ai/claude-code" && node install.cjs
claude --version
