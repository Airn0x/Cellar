#!/bin/sh
# Aider — AI pair programming, installed as an isolated uv tool.
set -e
if command -v aider >/dev/null 2>&1; then
	echo "already installed: $(aider --version 2>&1 | head -1)"
	exit 0
fi
export DEBIAN_FRONTEND=noninteractive
if ! command -v uv >/dev/null 2>&1; then
	apt-get update -qq
	apt-get install -y -qq python3 python3-venv curl ca-certificates git
	curl -LsSf https://astral.sh/uv/install.sh | sh
	# uv installs to ~/.local/bin; make it permanent for guest shells.
	# The $HOME here must stay literal — it's expanded by the guest's
	# shell at login, not by this installer.
	# shellcheck disable=SC2016
	grep -qs '.local/bin' /root/.profile || echo 'export PATH="$HOME/.local/bin:$PATH"' >>/root/.profile
fi
export PATH="$HOME/.local/bin:$PATH"
# uv hardlinks from its cache by default; Android's SELinux denies
# link() even inside a machine, so copy instead (field note #10).
export UV_LINK_MODE=copy
# A cache populated by a failed hardlink run is full of proot's .l2s
# symlink chains and later reads ELOOP — start this tool from clean.
rm -rf /root/.cache/uv /root/.local/share/uv/tools/aider-chat
uv tool install --python 3.12 aider-chat
ln -sf /root/.local/bin/aider /usr/local/bin/aider
aider --version
