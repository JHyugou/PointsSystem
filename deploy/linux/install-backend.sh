#!/usr/bin/env bash
# First installation only. Existing deployments require a deliberate upgrade.
set -euo pipefail
[[ $EUID -eq 0 ]] || { printf 'Run as root.\n' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
jar_path="$script_dir/../points-api.jar"
[[ -f $jar_path ]] || { printf 'points-api.jar is missing from the deployment bundle.\n' >&2; exit 1; }
for managed_path in /opt/points-home /etc/points-home /var/lib/points-home /var/log/points-home /var/backups/points-home; do
  [[ $(readlink -m "$managed_path") == "$managed_path" ]] || { printf 'Managed path must not be a symlink: %s\n' "$managed_path" >&2; exit 1; }
done
if [[ -e /etc/systemd/system/points-home.service || -e /opt/points-home/points-api.jar ]]; then
  printf 'Points Home is already installed; refusing to overwrite it.\n' >&2
  exit 1
fi
if ss -lntH | awk '{print $4}' | grep -qE ':18080$'; then
  printf 'Port 18080 is occupied. Inspect the owning service first.\n' >&2
  exit 1
fi
command -v dnf >/dev/null || { printf 'This installer expects Alibaba Cloud Linux 3 and dnf.\n' >&2; exit 1; }
dnf install -y java-17-openjdk-headless openssl curl tar gzip util-linux
java17=/usr/lib/jvm/jre-17-openjdk/bin/java
[[ -x $java17 ]] || { printf 'The Java 17 runtime is missing.\n' >&2; exit 1; }
"$java17" -version

if ! getent passwd pointshome >/dev/null; then
  useradd --system --user-group --home-dir /var/lib/points-home --shell /sbin/nologin pointshome
elif [[ $(getent passwd pointshome | cut -d: -f6) != /var/lib/points-home ]]; then
  printf 'The pointshome user belongs to another application.\n' >&2
  exit 1
fi
install -d -o root -g pointshome -m 0750 /opt/points-home
install -d -m 0700 /etc/points-home /var/backups/points-home
install -d -o pointshome -g pointshome -m 0700 \
  /var/lib/points-home /var/lib/points-home/data /var/lib/points-home/tmp /var/log/points-home

if [[ ! -f /etc/points-home/backend.env ]]; then
  if [[ -f /var/lib/points-home/data/points-home.mv.db ]]; then
    for key in otp-secret ai-master; do
      [[ -f /var/lib/points-home/data/$key.key ]] || { printf 'Imported database is missing %s.key. Restore its matching keys first.\n' "$key" >&2; exit 1; }
    done
  fi
  # Read from the local terminal; passwords never become command-line arguments.
  read -r -p 'Sender QQ email: ' mail_address </dev/tty
  [[ $mail_address =~ ^[A-Za-z0-9._+-]+@qq\.com$ ]] || { printf 'Enter a complete @qq.com address.\n' >&2; exit 1; }
  read -r -s -p 'QQ SMTP authorization code (hidden): ' mail_password </dev/tty
  printf '\n' >/dev/tty
  [[ $mail_password =~ ^[a-zA-Z]{16}$ ]] || { printf 'Enter the 16-letter QQ authorization code.\n' >&2; exit 1; }
  for key in otp-secret ai-master; do
    key_file="/var/lib/points-home/data/$key.key"
    if [[ ! -f $key_file ]]; then
      openssl rand -base64 32 > "$key_file"
      chown pointshome:pointshome "$key_file"
      chmod 0600 "$key_file"
    fi
  done
  umask 077
  cat > /etc/points-home/backend.env <<EOF
APP_MAIL_MODE=smtp
MAIL_HOST=smtp.qq.com
MAIL_PORT=465
MAIL_SSL_ENABLED=true
MAIL_STARTTLS_ENABLED=false
MAIL_STARTTLS_REQUIRED=false
MAIL_USERNAME=$mail_address
MAIL_FROM=$mail_address
MAIL_PASSWORD=$mail_password
APP_OTP_SECRET=$(tr -d '\r\n' < /var/lib/points-home/data/otp-secret.key)
APP_AI_MASTER_KEY=$(tr -d '\r\n' < /var/lib/points-home/data/ai-master.key)
PORT=18080
EOF
  unset mail_password
fi
chown -hR pointshome:pointshome /var/lib/points-home/data
find /var/lib/points-home/data -type d -exec chmod 0700 {} +
find /var/lib/points-home/data -type f -exec chmod 0600 {} +
chmod 0600 /etc/points-home/backend.env
install -o root -g pointshome -m 0640 "$jar_path" /opt/points-home/points-api.jar
install -m 0750 "$script_dir/backup.sh" /opt/points-home/backup.sh
install -m 0750 "$script_dir/verify-backup.sh" /opt/points-home/verify-backup.sh
install -m 0644 "$script_dir/points-home.service" /etc/systemd/system/points-home.service
install -m 0644 "$script_dir/points-home-backup.service" /etc/systemd/system/points-home-backup.service
install -m 0644 "$script_dir/points-home-backup.timer" /etc/systemd/system/points-home-backup.timer
systemctl daemon-reload
systemctl enable --now points-home.service
ready=false
for ((attempt=0; attempt<40; attempt++)); do
  if curl --fail --silent --max-time 2 http://127.0.0.1:18080/api/v1/health >/dev/null; then ready=true; break; fi
  sleep 1
done
$ready || { printf 'API did not become healthy. Check journalctl -u points-home.\n' >&2; exit 1; }
systemctl enable --now points-home-backup.timer
printf 'API healthy at 127.0.0.1:18080; daily backup timer enabled.\n'
