#!/bin/sh
# code-server — VS Code as a web app. Start it with:
#   cellar start <machine> -- 'code-server --bind-addr 0.0.0.0:8080'
# Password lands in /root/.config/code-server/config.yaml on first run.
set -e
if command -v code-server >/dev/null 2>&1; then
	echo "already installed: $(code-server --version 2>&1 | head -1)"
	exit 0
fi
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates git
curl -fsSL https://code-server.dev/install.sh | sh
code-server --version
echo "start it with: cellar start <machine> -- 'code-server --bind-addr 0.0.0.0:8080'"
