#!/usr/bin/env bash
# IPv4 HTTPS for private app testing; no domain or custom Android trust store.
set -euo pipefail
[[ $EUID -eq 0 ]] || { printf 'Run as root.\n' >&2; exit 1; }
server_ip=${1:-}
[[ $server_ip =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { printf 'Usage: bash configure-https.sh PUBLIC_IPV4\n' >&2; exit 1; }
IFS=. read -r -a octets <<< "$server_ip"
for octet in "${octets[@]}"; do
  ((10#$octet <= 255)) || { printf 'Invalid IPv4.\n' >&2; exit 1; }
done
[[ -f /opt/points-home/points-api.jar ]] || { printf 'Install and check the backend first.\n' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ ! -f /etc/nginx/conf.d/points-home-https.conf ]] || { printf 'HTTPS already configured; inspect it before changing it.\n' >&2; exit 1; }

dnf install -y nginx python3.11 python3.11-pip
# The existing ECS used an inline server block without loading conf.d.
# Check the active configuration before writing files that Nginx could ignore.
nginx -T 2>&1 | grep -E '^[[:space:]]*include[[:space:]]+/etc/nginx/conf\.d/\*\.conf;' >/dev/null || {
  printf 'Nginx must load /etc/nginx/conf.d/*.conf; inspect its main configuration first.\n' >&2
  exit 1
}
install -d -m 0755 /var/lib/points-home-acme
if [[ ! -x /opt/points-home-certbot/bin/certbot ]]; then
  python3.11 -m venv /opt/points-home-certbot
  /opt/points-home-certbot/bin/python -m pip install --upgrade pip
  /opt/points-home-certbot/bin/python -m pip install 'certbot==5.8.0'
fi
/opt/points-home-certbot/bin/python -c \
  'import importlib.metadata as m; v=tuple(map(int,m.version("certbot").split(".")[:2])); assert v >= (5,4), "Certbot >= 5.4 required"'

# Inspect old services before running this script. Existing Nginx sites are kept.
sed "s/__SERVER_IP__/$server_ip/g" "$script_dir/nginx-http.conf.template" \
  > /etc/nginx/conf.d/points-home-http.conf
nginx -t
if command -v getenforce >/dev/null && [[ $(getenforce) == Enforcing ]]; then
  setsebool -P httpd_can_network_connect 1
fi
if systemctl is-active --quiet nginx.service; then
  systemctl reload nginx.service
else
  systemctl enable --now nginx.service
fi

# HTTP-01 needs TCP 80 reachable through the ECS security group and host firewall.
if command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
  firewall-cmd --permanent --add-service=http
  firewall-cmd --permanent --add-service=https
  firewall-cmd --reload
fi
/opt/points-home-certbot/bin/certbot certonly --webroot \
  --webroot-path /var/lib/points-home-acme --ip-address "$server_ip" \
  --required-profile shortlived --cert-name points-home \
  --non-interactive --agree-tos --register-unsafely-without-email

sed "s/__SERVER_IP__/$server_ip/g" "$script_dir/nginx-https.conf.template" \
  > /etc/nginx/conf.d/points-home-https.conf
nginx -t
systemctl reload nginx.service
install -m 0750 "$script_dir/reload-nginx.sh" /opt/points-home/reload-nginx.sh
install -m 0644 "$script_dir/points-home-cert-renew.service" /etc/systemd/system/points-home-cert-renew.service
install -m 0644 "$script_dir/points-home-cert-renew.timer" /etc/systemd/system/points-home-cert-renew.timer
systemctl daemon-reload
systemctl enable --now points-home-cert-renew.timer
/opt/points-home-certbot/bin/certbot renew --cert-name points-home --dry-run --run-deploy-hooks
curl --fail --silent --max-time 10 "https://$server_ip/api/v1/health"
printf '\nHTTPS configured and renewal dry run passed.\n'
