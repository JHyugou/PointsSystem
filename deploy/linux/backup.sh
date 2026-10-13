#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { printf 'Run as root.\n' >&2; exit 1; }
readonly backup_root=/var/backups/points-home
install -d -m 0700 "$backup_root"
[[ $(readlink -f "$backup_root") == "$backup_root" ]] || { printf 'Backup path must not be a symlink.\n' >&2; exit 1; }
exec 9>/run/points-home-backup.lock
flock -n 9 || { printf 'Another backup is running.\n' >&2; exit 1; }

stage=$(mktemp -d "$backup_root/.snapshot-XXXXXXXX")
resume=false
cleanup() {
  local result=$?
  if $resume; then
    if ! systemctl start points-home.service; then
      printf 'ERROR: backup could not restart Points Home.\n' >&2
      result=1
    fi
  fi
  case "$stage" in "$backup_root"/.snapshot-*) rm -rf -- "$stage" ;; esac
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

if systemctl is-active --quiet points-home.service; then
  resume=true
  systemctl stop points-home.service
fi
# H2 and the photos must be copied with all application writes stopped.
cp -a /var/lib/points-home/data "$stage/data"
install -m 0600 /etc/points-home/backend.env "$stage/backend.env"
if $resume; then
  systemctl start points-home.service
  resume=false
fi

archive="$backup_root/points-home-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
tar -C "$stage" -cf - data backend.env | gzip -1 > "$stage/archive.tar.gz"
tar -tzf "$stage/archive.tar.gz" >/dev/null
install -m 0600 "$stage/archive.tar.gz" "$archive"
(cd "$backup_root" && sha256sum "${archive##*/}" > "${archive##*/}.sha256")
find "$backup_root" -maxdepth 1 -type f -name 'points-home-*.tar.gz*' -mtime +13 -delete
printf 'Backup created: %s\n' "$archive"
