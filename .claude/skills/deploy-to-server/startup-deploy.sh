#!/bin/bash
# Boot-time deploy for the live server, installed as the VM's `startup-script`
# metadata by the deploy-to-server skill. Cloud sessions can't SSH to the VM
# (the egress proxy blocks port 22 and the WebSocket IAP tunnel), so the jar
# is swapped here on boot instead.
#
# Reads instance metadata values:
#   aiciv-jar-url       raw GitHub URL of the jar, pinned to a commit
#   aiciv-jar-sha256    expected SHA-256 of that jar
#   aiciv-config        optional; "key=value,key=value" settings written into
#                       config/aicivilization.json (numbers and true/false
#                       only; a key missing from the file is added)
#   aiciv-reset-world   optional; any new value (e.g. a date) starts a fresh
#                       world once: the old one is moved aside, not deleted
# Works out everything to change first, then stops the server once, applies
# it all (jar, then settings, then world), and starts it once: a new world is
# never first opened by the old jar. Does nothing if nothing needs changing.
# Every line it logs starts with AICIV-DEPLOY (visible in the serial console).

MD=http://metadata.google.internal/computeMetadata/v1/instance/attributes
log() { echo "AICIV-DEPLOY: $*"; }
attr() { curl -fsS -H 'Metadata-Flavor: Google' "$MD/$1"; }

MODS=$(ls -d /home/*/mcserver/mods 2>/dev/null | head -n 1)
[ -n "$MODS" ] || { log "no /home/*/mcserver/mods found; refusing"; exit 0; }
SERVER=$(dirname "$MODS")
OWNER=$(stat -c %U:%G "$MODS")

# The newest crash report since the last boot, if any, for the serial log
# (nothing else from the server reaches it). Lines naming keys or tokens are left out.
CRASH=$(ls -t "$SERVER"/crash-reports/*.txt 2>/dev/null | head -n 1)
if [ -n "$CRASH" ] && [ "$CRASH" -nt /proc/1 ] 2>/dev/null || [ -n "$CRASH" ] && [ "$(( $(date +%s) - $(stat -c %Y "$CRASH") ))" -lt 86400 ]; then
  log "latest crash report: $(basename "$CRASH")"
  head -n 60 "$CRASH" | grep -viE 'key|token|secret|password' | sed 's/^/AICIV-CRASH: /'
fi

# The previous run's warnings and errors (the server's own log never reaches
# the serial console): from latest.log if the server hasn't rotated it yet,
# else the newest archived log. Lines naming keys or tokens are left out.
PREV=$(ls -t "$SERVER"/logs/latest.log "$SERVER"/logs/*.log.gz 2>/dev/null | head -n 1)
if [ -n "$PREV" ]; then
  log "previous server log: $(basename "$PREV")"
  { case "$PREV" in *.gz) zcat "$PREV";; *) cat "$PREV";; esac; } 2>/dev/null \
    | grep -E "WARN|ERROR|Can't keep up|Exception|at com\.aicivilization" | grep -viE 'key|token|secret|password' \
    | tail -n 60 | cut -c1-300 | sed 's/^/AICIV-LOG: /'
fi

# Clearing out old backups, once per new aiciv-cleanup value: the old worlds
# moved aside by resets (backup-world-*), the old jars kept by deploys
# (backup-aiciv-*; every jar is in git at its commit anyway), and crash
# reports. The live world, jar, config and logs are never touched.
CLEANUP=$(attr aiciv-cleanup 2>/dev/null || true)
CLEAN_FILE="$SERVER/.aiciv-cleanup-done"
if [ -n "$CLEANUP" ] && [ "$CLEANUP" != "$(cat "$CLEAN_FILE" 2>/dev/null)" ]; then
  for OLDDIR in "$SERVER"/backup-world-* "$SERVER"/backup-aiciv-*; do
    [ -d "$OLDDIR" ] || continue
    log "cleanup $CLEANUP: removing $(basename "$OLDDIR") ($(du -sh "$OLDDIR" | cut -f1))"
    rm -rf -- "$OLDDIR"
  done
  N=$(ls "$SERVER"/crash-reports/*.txt 2>/dev/null | wc -l)
  rm -f -- "$SERVER"/crash-reports/*.txt
  log "cleanup $CLEANUP: removed $N crash reports; $(df -h "$SERVER" | tail -n 1 | awk '{print $4}') free"
  echo "$CLEANUP" > "$CLEAN_FILE"
fi

# --- what's wanted -----------------------------------------------------------

# The jar: downloaded and checked now, installed below.
NEWJAR=""
URL=$(attr aiciv-jar-url) || URL=""
WANT=$(attr aiciv-jar-sha256) || WANT=""
OLD=$(ls "$MODS"/aicivilization-*.jar 2>/dev/null)
if [ -z "$URL" ] || [ -z "$WANT" ]; then
  log "no aiciv-jar-url/aiciv-jar-sha256 metadata; leaving the jar"
elif [ "$(echo "$OLD" | grep -c .)" -gt 1 ]; then
  log "several mod jars in $MODS; leaving the jar"
else
  HAVE=$([ -n "$OLD" ] && sha256sum "$OLD" | cut -d' ' -f1)
  if [ "$HAVE" = "$WANT" ]; then
    log "already running $WANT"
  else
    NEWJAR=$(mktemp)
    if ! curl -fsSL --retry 5 --retry-delay 3 -o "$NEWJAR" "$URL"; then
      log "download failed: $URL; keeping old jar"; rm -f "$NEWJAR"; NEWJAR=""
    elif [ "$(sha256sum "$NEWJAR" | cut -d' ' -f1)" != "$WANT" ]; then
      log "checksum mismatch: want $WANT; keeping old jar"; rm -f "$NEWJAR"; NEWJAR=""
    fi
  fi
fi

# Settings that differ from the file.
CONFIG="$SERVER/config/aicivilization.json"
SETTINGS=$(attr aiciv-config 2>/dev/null || true)
CHANGES=""
if [ -n "$SETTINGS" ] && [ -f "$CONFIG" ]; then
  for PAIR in $(echo "$SETTINGS" | tr ',' ' '); do
    K=${PAIR%%=*}; V=${PAIR#*=}
    if ! echo "$K" | grep -qE '^[A-Za-z]+$' || ! echo "$V" | grep -qE '^(-?[0-9]+(\.[0-9]+)?|true|false)$'; then
      log "ignoring setting '$PAIR'"; continue
    fi
    grep -qE "\"$K\": $V(,|$)" "$CONFIG" || CHANGES="$CHANGES $K=$V"
  done
fi

# A fresh world, once per new aiciv-reset-world value.
RESET=$(attr aiciv-reset-world 2>/dev/null || true)
DONE_FILE="$SERVER/.aiciv-reset-done"
LEVEL=$(sed -n 's/^level-name=//p' "$SERVER/server.properties" 2>/dev/null | tail -n 1)
LEVEL=${LEVEL:-world}
DORESET=""
if [ -n "$RESET" ] && [ "$RESET" != "$(cat "$DONE_FILE" 2>/dev/null)" ]; then
  case "$LEVEL" in */*|.*) log "odd level-name '$LEVEL'; not resetting";; *) DORESET=1;; esac
fi

if [ -z "$NEWJAR" ] && [ -z "$CHANGES" ] && [ -z "$DORESET" ]; then
  log "nothing to do"
  exit 0
fi

# --- apply, with the server stopped once --------------------------------------

systemctl stop minecraft.service

if [ -n "$NEWJAR" ]; then
  BACKUP=""
  if [ -n "$OLD" ]; then
    BACKUP="$SERVER/backup-aiciv-$(date -u +%Y%m%d-%H%M%S)"
    mkdir -p "$BACKUP" && mv "$OLD" "$BACKUP/" && chown -R "$OWNER" "$BACKUP"
  fi
  install -o "${OWNER%%:*}" -g "${OWNER##*:}" -m 644 "$NEWJAR" "$MODS/$(basename "$URL")"
  rm -f "$NEWJAR"
  log "deployed $WANT (was ${HAVE:-none}); backup in ${BACKUP:-none}"
fi

for PAIR in $CHANGES; do
  K=${PAIR%%=*}; V=${PAIR#*=}
  if grep -qE "\"$K\": " "$CONFIG"; then
    sed -i -E "s/(\"$K\": )[^,]*(,?)$/\1$V\2/" "$CONFIG"
  else
    # Not in the file yet (the mod's default applies): add it as the first setting.
    sed -i "0,/{/s//{\n  \"$K\": $V,/" "$CONFIG"
  fi
done
[ -n "$CHANGES" ] && log "settings changed:$CHANGES"

if [ -n "$DORESET" ]; then
  if [ -d "$SERVER/$LEVEL" ]; then
    OLDWORLD="$SERVER/backup-world-$(date -u +%Y%m%d-%H%M%S)"
    mv "$SERVER/$LEVEL" "$OLDWORLD"
    log "reset world $RESET: moved $LEVEL to $OLDWORLD"
  else
    log "reset world $RESET: no $LEVEL folder; a new one will be made"
  fi
  echo "$RESET" > "$DONE_FILE"
fi

systemctl start minecraft.service
log "server started"
