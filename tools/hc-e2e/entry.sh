#!/usr/bin/env bash
# Makes a throwaway key pair for the test client, starts sshd, then (as dev) the herdr server
# and herdr-core, as a real computer running both would have them.
set -e
ssh-keygen -A >/dev/null
install -d -m 700 -o dev -g dev /home/dev/.ssh
[ -f /keys/id ] || ssh-keygen -q -t ed25519 -N "" -f /keys/id
chmod 644 /keys/id /keys/id.pub
install -m 600 -o dev -g dev /keys/id.pub /home/dev/.ssh/authorized_keys
/usr/sbin/sshd
su - dev -c 'cd ~ && (setsid herdr server >/tmp/herdr-server.log 2>&1 < /dev/null &) ; sleep 2; hc server start >/tmp/hc.log 2>&1 || true'
echo ready
sleep infinity
