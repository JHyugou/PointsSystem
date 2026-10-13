#!/usr/bin/env bash
# Upgrade with traffic paused, a cold backup, and matching JAR/database rollback.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run as root.' >&2; exit 1; }
new_jar=$(readlink -f "${1:-}")
expected=${2:-}
[[ -f $new_jar && $expected =~ ^[a-f0-9]{64}$ ]] || { echo 'Pass the new JAR and SHA256.' >&2; exit 1; }
[[ $(sha256sum "$new_jar" | cut -d' ' -f1) == "$expected" ]] || { echo 'New JAR checksum mismatch.' >&2; exit 1; }
readonly live_data=/var/lib/points-home/data
readonly live_jar=/opt/points-home/points-api.jar
readonly config=/etc/nginx/conf.d/points-home-https.conf
readonly maintenance=/run/points-home-maintenance
readonly backup_root=/var/backups/points-home
readonly java=/usr/lib/jvm/jre-17-openjdk/bin/java
for path in "$live_data" "$live_jar" "$config" "$backup_root"; do
  [[ $(readlink -f "$path") == "$path" ]] || { echo 'Managed path missing or is a symlink.' >&2; exit 1; }
done
[[ ! -e $maintenance ]] || { echo 'Maintenance is already active; inspect before retrying.' >&2; exit 1; }
systemctl is-active --quiet points-home.service
exec 9>/run/points-home-backup.lock
flock -n 9 || { echo 'A backup or upgrade is already running.' >&2; exit 1; }
stage=$(mktemp -d "$backup_root/upgrade-XXXXXXXX")
chmod 0700 "$stage"
cp -a "$live_jar" "$stage/points-api.jar"
cp -a "$config" "$stage/nginx.conf"
install -m 0600 /etc/points-home/backend.env "$stage/backend.env"
stopped=false
success=false
replacement=false
h2_runtime=''
ready() {
  local attempt
  for ((attempt=0; attempt<45; attempt++)); do
    if curl -fsS --max-time 2 http://127.0.0.1:18080/api/v1/health > "$stage/health.json"; then return 0; fi
    if ! systemctl is-active --quiet points-home.service; then return 1; fi
    sleep 1
  done
  return 1
}
cleanup() {
  local result=$?
  if ! $success && $stopped; then
    echo 'Upgrade failed; restoring the previous JAR and matching data.' >&2
    systemctl stop points-home.service || true
    install -o root -g pointshome -m 0640 "$stage/points-api.jar" "$live_jar"
    if $replacement; then
      [[ $(readlink -f "$live_data") == "$live_data" ]] || exit 1
      mv "$live_data" "$stage/failed-data"
      cp -a "$stage/data" "$live_data"
    fi
    if ! systemctl start points-home.service || ! ready; then
      echo "Rollback needs attention. Maintenance remains active. Backup: $stage" >&2
      exit 1
    fi
  fi
  if $success || ! $stopped || systemctl is-active --quiet points-home.service; then
    rm -f -- "$maintenance"
  fi
  [[ -z $h2_runtime ]] || rm -f -- "$h2_runtime"
  echo "Upgrade backup retained: $stage"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

python3.11 - "$config" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1]); source = p.read_text()
guard = 'if (-f /run/points-home-maintenance) { return 503; }'
needle = 'proxy_pass http://127.0.0.1:18080;'
if guard not in source:
    if source.count(needle) != 1:
        raise RuntimeError('Unexpected reverse proxy configuration')
    p.write_text(source.replace(needle, guard + '\n        ' + needle))
PY
if ! nginx -t; then cp -a "$stage/nginx.conf" "$config"; exit 1; fi
systemctl reload nginx
touch "$maintenance"
# Wait for existing proxy requests to drain before stopping the database writer.
sleep 2
systemctl stop points-home.service
stopped=true
cp -a "$live_data" "$stage/data"
tar -C "$stage" -czf "$stage/pre-upgrade.tar.gz" data backend.env points-api.jar nginx.conf
chmod 0600 "$stage/pre-upgrade.tar.gz"
sha256sum "$stage/pre-upgrade.tar.gz" > "$stage/pre-upgrade.tar.gz.sha256"
python3.11 - "$new_jar" "$stage/h2.jar" <<'PY'
import pathlib, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    names = [n for n in jar.namelist() if n.startswith('BOOT-INF/lib/h2-') and n.endswith('.jar')]
    if len(names) != 1: raise RuntimeError('Missing H2 runtime')
    pathlib.Path(sys.argv[2]).write_bytes(jar.read(names[0]))
PY
h2_runtime=$(mktemp /var/lib/points-home/tmp/upgrade-h2-XXXXXXXX.jar)
install -o pointshome -g pointshome -m 0600 "$stage/h2.jar" "$h2_runtime"
sql='SELECT COUNT(*) FROM app_user; SELECT COUNT(*) FROM point_account; SELECT SUM(balance) FROM point_account; SELECT COUNT(*) FROM point_ledger; SELECT COUNT(*) FROM reward_item; SELECT COUNT(*) FROM reward_redemption; SELECT COUNT(*) FROM homework_submission;'
rows() {
  runuser -u pointshome -- "$java" -Xmx128m -cp "$h2_runtime" org.h2.tools.Shell -url "jdbc:h2:file:$live_data/points-home;IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE" \
    -user sa -password '' -sql "$sql" | sed -E '/^\([0-9]+ row/d'
}
rows > "$stage/rows-before.txt"
! grep -Ei 'Exception|Error:' "$stage/rows-before.txt"
replacement=true
install -o root -g pointshome -m 0640 "$new_jar" "$live_jar"
systemctl reset-failed points-home.service || true
systemctl start points-home.service
ready || { journalctl -u points-home.service -n 12 --no-pager; exit 1; }
systemctl stop points-home.service
rows > "$stage/rows-after.txt"
! grep -Ei 'Exception|Error:' "$stage/rows-after.txt"
diff -u "$stage/rows-before.txt" "$stage/rows-after.txt"
systemctl start points-home.service
ready
success=true
rm -f -- "$maintenance"
echo 'Upgrade complete. Existing account, points, receipt and homework counts preserved.'
cat "$stage/health.json"
