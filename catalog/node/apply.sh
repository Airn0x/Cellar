#!/bin/sh
set -e
if command -v apk >/dev/null; then
	apk add --no-cache nodejs npm
elif command -v apt-get >/dev/null; then
	export DEBIAN_FRONTEND=noninteractive
	apt-get update -qq
	apt-get install -y -qq nodejs npm ca-certificates
else
	echo "unsupported distro (need apk or apt-get)" >&2
	exit 1
fi
node --version
npm --version
