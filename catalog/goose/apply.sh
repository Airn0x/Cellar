#!/bin/sh
# Goose — Block's on-machine AI agent (official install script).
set -e
if command -v goose >/dev/null 2>&1; then
	echo "already installed: $(goose --version 2>&1 | head -1)"
	exit 0
fi
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates bzip2 libxcb1 git
# CONFIGURE must be set for `bash`, not for `curl` — the installer's
# interactive configurator needs a TTY that no machine command has.
curl -fsSL https://github.com/block/goose/releases/download/stable/download_cli.sh | CONFIGURE=false bash
ln -sf /root/.local/bin/goose /usr/local/bin/goose
goose --version
