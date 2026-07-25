#!/bin/sh
# Python 3 + uv (astral.sh installer picks the right arm64 build)
set -e
if command -v apk >/dev/null; then
	apk add --no-cache python3 curl ca-certificates
elif command -v apt-get >/dev/null; then
	export DEBIAN_FRONTEND=noninteractive
	apt-get update -qq
	apt-get install -y -qq python3 python3-venv curl ca-certificates
else
	echo "unsupported distro (need apk or apt-get)" >&2
	exit 1
fi
curl -fsSL https://astral.sh/uv/install.sh | UV_INSTALL_DIR=/usr/local/bin sh
python3 --version
uv --version
