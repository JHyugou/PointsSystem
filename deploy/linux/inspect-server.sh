#!/usr/bin/env bash
# Read-only: no installation, restart, deletion, or environment dump.
set -u
printf '== Operating system ==\n'
cat /etc/os-release
printf '\n== Memory ==\n'
free -m
printf '\n== Disk ==\n'
df -h / /var /opt
printf '\n== Listening TCP ports ==\n'
ss -lntp
printf '\n== Largest processes (names only) ==\n'
ps -eo pid,comm,rss --sort=-rss | head -n 12
printf '\n== Relevant services ==\n'
systemctl --no-pager --plain list-units --type=service --state=running \
  'nginx*' 'httpd*' 'java*' 'mysql*' 'maria*' 'docker*' 'points-home*'
printf '\n== Java ==\n'
if command -v java >/dev/null; then java -version 2>&1; else printf 'Java not installed\n'; fi
printf '\n== Firewall ==\n'
if command -v firewall-cmd >/dev/null; then firewall-cmd --list-services 2>/dev/null || true; fi
printf '\n== SELinux ==\n'
if command -v getenforce >/dev/null; then getenforce; fi
