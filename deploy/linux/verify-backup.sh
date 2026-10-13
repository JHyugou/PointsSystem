#!/usr/bin/env bash
# Restore a backup into an isolated directory and localhost port. Never replace live data.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run as root.' >&2; exit 1; }
archive=${1:-}
backup_root=/var/backups/points-home
restore_root=/var/lib/points-home-restore-check
unit=points-home-restore-check.service
java17=/usr/lib/jvm/jre-17-openjdk/bin/java
[[ -f $archive && -f $archive.sha256 ]] || { echo 'Pass an existing backup archive with its checksum.' >&2; exit 1; }
[[ $(readlink -f "$archive") == "$backup_root"/points-home-*.tar.gz ]] || { echo 'Archive must be inside the managed backup directory.' >&2; exit 1; }
[[ $(readlink -m "$restore_root") == "$restore_root" ]] || { echo 'Restore path must not be a symlink.' >&2; exit 1; }
[[ -x $java17 && -f /opt/points-home/points-api.jar ]] || { echo 'Install the application first.' >&2; exit 1; }
if ss -lntH | awk '{print $4}' | grep -qE ':18081$'; then
  echo 'Restore-check port 18081 is occupied.' >&2; exit 1
fi
if systemctl is-active --quiet "$unit"; then
  echo 'A restore check is already running.' >&2; exit 1
fi
exec 9>/run/points-home-restore-check.lock
flock -n 9 || { echo 'Another restore check holds the lock.' >&2; exit 1; }
(cd "$backup_root" && sha256sum -c "${archive##*/}.sha256")
install -d -o root -g pointshome -m 0750 "$restore_root"
stage=$(mktemp -d "$restore_root/check-XXXXXXXX")
cleanup() {
  local result=$?
  systemctl stop "$unit" >/dev/null 2>&1 || true
  systemctl reset-failed "$unit" >/dev/null 2>&1 || true
  case "$stage" in "$restore_root"/check-*) rm -rf -- "$stage" ;; esac
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Reject unsafe entries, restore bytes, and check that both encryption keys match the environment.
python3.11 - "$archive" "$stage" <<'PY'
import hashlib, pathlib, sys, tarfile, zipfile
archive, stage = sys.argv[1], pathlib.Path(sys.argv[2])
with tarfile.open(archive, "r:gz") as backup:
    members = backup.getmembers()
    for entry in members:
        path = pathlib.PurePosixPath(entry.name)
        if path.is_absolute() or ".." in path.parts or not path.parts:
            raise RuntimeError("Unsafe archive path")
        if path.parts[0] not in ("data", "backend.env") or not (entry.isfile() or entry.isdir()):
            raise RuntimeError("Unexpected archive entry")
    backup.extractall(stage, members=members, filter="data")
    for entry in members:
        if entry.isfile():
            expected = hashlib.sha256(backup.extractfile(entry).read()).digest()
            if expected != hashlib.sha256((stage / entry.name).read_bytes()).digest():
                raise RuntimeError("Restored file bytes differ")
env = dict(line.split("=", 1) for line in (stage / "backend.env").read_text().splitlines() if "=" in line)
for name, variable in (("otp-secret", "APP_OTP_SECRET"), ("ai-master", "APP_AI_MASTER_KEY")):
    if (stage / "data" / (name + ".key")).read_text().strip() != env.get(variable):
        raise RuntimeError("Restored secret does not match its data key")
with zipfile.ZipFile("/opt/points-home/points-api.jar") as app:
    matches = [name for name in app.namelist() if name.startswith("BOOT-INF/lib/h2-") and name.endswith(".jar")]
    if len(matches) != 1:
        raise RuntimeError("Cannot locate the bundled H2 library")
    (stage / "h2.jar").write_bytes(app.read(matches[0]))
print("Archive bytes and matching keys verified.")
PY

chown pointshome:pointshome "$stage" "$stage/h2.jar"
chmod 0700 "$stage"
chmod 0600 "$stage/h2.jar" "$stage/backend.env"
chown -R pointshome:pointshome "$stage/data"
install -d -o pointshome -g pointshome -m 0700 "$stage/tmp" "$stage/logs"
db_url="jdbc:h2:file:$stage/data/points-home;IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE"
sql='SELECT COUNT(*) AS USERS FROM app_user; SELECT COUNT(*) AS POINT_ACCOUNTS FROM point_account; SELECT COUNT(*) AS POINT_LEDGER_ROWS FROM point_ledger; SELECT COUNT(*) AS HOMEWORK_ROWS FROM homework_submission; SELECT COUNT(*) AS EMAIL_CODES FROM email_code;'
runuser -u pointshome -- "$java17" -Xmx128m -cp "$stage/h2.jar" org.h2.tools.Shell \
  -url "$db_url" -user sa -password '' -sql "$sql" > "$stage/rows-before.txt"
! grep -Ei 'Exception|Error:' "$stage/rows-before.txt" || { echo 'Cannot query restored database.' >&2; exit 1; }
cat "$stage/rows-before.txt"

systemd-run --unit="$unit" --property=Type=simple \
  --property=User=pointshome --property=Group=pointshome \
  --property="WorkingDirectory=$stage" --property="EnvironmentFile=$stage/backend.env" \
  --property=MemoryMax=600M --property=TimeoutStopSec=150s \
  "$java17" -Xms64m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=32m -Xss512k \
  -Djava.awt.headless=true "-Djava.io.tmpdir=$stage/tmp" \
  -jar /opt/points-home/points-api.jar --spring.profiles.active=prod \
  --server.port=18081 "--spring.datasource.url=$db_url" --app.mail.mode=console \
  "--app.homework.storage-dir=$stage/data/homework" \
  "--spring.servlet.multipart.location=$stage/tmp" "--logging.file.name=$stage/logs/app.log"
ready=false
for ((attempt=0; attempt<45; attempt++)); do
  if curl --fail --silent --max-time 2 http://127.0.0.1:18081/api/v1/health > "$stage/health.json"; then ready=true; break; fi
  if ! systemctl is-active --quiet "$unit"; then break; fi
  sleep 1
done
$ready || { journalctl -u "$unit" -n 12 --no-pager; echo 'Restored API did not become healthy.' >&2; exit 1; }
cat "$stage/health.json"
ss -lntH | awk '{print $4}' | grep -Fx -e '127.0.0.1:18081' -e '[::ffff:127.0.0.1]:18081' >/dev/null
systemctl stop "$unit"
runuser -u pointshome -- "$java17" -Xmx128m -cp "$stage/h2.jar" org.h2.tools.Shell \
  -url "$db_url" -user sa -password '' -sql "$sql" > "$stage/rows-after.txt"
! grep -Ei 'Exception|Error:' "$stage/rows-after.txt" || { echo 'Cannot query restored database after startup.' >&2; exit 1; }
# H2 adds elapsed-time lines; compare the actual result rows.
diff -u <(sed '/^(.* ms)$/d' "$stage/rows-before.txt") <(sed '/^(.* ms)$/d' "$stage/rows-after.txt")
curl --fail --silent --max-time 10 http://127.0.0.1:18080/api/v1/health
echo
echo 'RESTORE_CHECK_OK: restored API started on localhost; business row counts preserved; live API healthy.'
