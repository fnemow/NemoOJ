#!/usr/bin/env bash
set -euo pipefail
[ "$(id -u)" = 0 ] || { printf 'Run as root\n' >&2; exit 1; }
role="${1:?core or ai}";peer="${2:?WireGuard peer IP}"
case "$role:$peer" in core:10.88.0.2|ai:10.88.0.1) ;; *) exit 1 ;; esac
source_dir="$(cd "$(dirname "$0")" && pwd)"
command -v gpg >/dev/null
install -d -m 700 /etc/nemo-oj /var/lib/nemo-ops
install -d -m 755 /usr/local/lib/nemo-ops
install -m 644 "$source_dir"/*.py /usr/local/lib/nemo-ops/
if ! id nemo-backup >/dev/null 2>&1; then useradd --system --create-home --shell /bin/sh nemo-backup; fi
receiver_role=ai;[ "$role" = ai ] && receiver_role=core
install -d -o nemo-backup -g nemo-backup -m 700 /var/lib/nemo-backup "/var/lib/nemo-backup/$receiver_role"
install -d -o nemo-backup -g nemo-backup -m 700 /home/nemo-backup/.ssh
if [ ! -f /etc/nemo-oj/backup_ssh ]; then ssh-keygen -q -t ed25519 -N '' -f /etc/nemo-oj/backup_ssh; fi
printf 'NEMO_ROLE=%s\n' "$role" > /etc/nemo-oj/ops.env
printf '{"peer":"%s"}\n' "$peer" > /etc/nemo-oj/ops.json
chmod 600 /etc/nemo-oj/ops.env /etc/nemo-oj/ops.json /etc/nemo-oj/backup_ssh
install -m 644 "$source_dir"/../systemd/nemo-{backup,monitor}.{service,timer} /etc/systemd/system/
systemctl daemon-reload
printf 'Sender public key:\n';cat /etc/nemo-oj/backup_ssh.pub
printf 'Host public key:\n';cat /etc/ssh/ssh_host_ed25519_key.pub
printf 'Timers are not enabled until the first verified backup succeeds.\n'
