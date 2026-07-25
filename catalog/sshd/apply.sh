#!/bin/sh
# OpenSSH server for a personal lab machine. Port 2222 (no <1024 without
# root). Root login by password is deliberate here — set one before
# starting: cellar exec <machine> -- passwd
set -e
if command -v apk >/dev/null; then
	apk add --no-cache openssh
elif command -v apt-get >/dev/null; then
	export DEBIAN_FRONTEND=noninteractive
	apt-get update -qq
	apt-get install -y -qq openssh-server
else
	echo "unsupported distro (need apk or apt-get)" >&2
	exit 1
fi
ssh-keygen -A
mkdir -p /etc/ssh/sshd_config.d
cat > /etc/ssh/sshd_config.d/cellar.conf <<'EOF'
Port 2222
PermitRootLogin yes
EOF
echo "sshd ready. set a password, then start the machine with:"
echo "  cellar exec <machine> -- passwd"
echo "  cellar start <machine> -- '/usr/sbin/sshd -D -e'"
