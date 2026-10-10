#!/bin/bash
# Boot-time deploy for the live server, installed as the VM's `startup-script`
# metadata by the deploy-to-server skill. Cloud sessions can't SSH to the VM
# (the egress proxy blocks port 22 and the WebSocket IAP tunnel), so the jar
# is swapped here on boot instead.
#
# Reads instance metadata values:
#   aiciv-jar-url       raw GitHub URL of the jar, pinned to a commit
#   aiciv-jar-sha256    expected SHA-256 of that jar
#   aiciv-reset-world   optional; any new value (e.g. a date) starts a fresh
#                       world once: the old one is moved aside, not deleted
# Idempotent: does nothing if mods/ already holds that checksum and the
# reset value is one already acted on.
# Every line it logs starts with AICIV-DEPLOY (visible in the serial console).

MD=http://metadata.google.internal/computeMetadata/v1/instance/attributes
log() { echo "AICIV-DEPLOY: $*"; }
attr() { curl -fsS -H 'Metadata-Flavor: Google' "$MD/$1"; }

URL=$(attr aiciv-jar-url) || { log "no aiciv-jar-url metadata; nothing to do"; exit 0; }
WANT=$(attr aiciv-jar-sha256) || { log "no aiciv-jar-sha256 metadata; refusing"; exit 0; }

MODS=$(ls -d /home/*/mcserver/mods 2>/dev/null | head -n 1)
[ -n "$MODS" ] || { log "no /home/*/mcserver/mods found; refusing"; exit 0; }
SERVER=$(dirname "$MODS")

# A fresh world, once per new aiciv-reset-world value. The old world is kept
# beside the server as backup-world-<time>; the server makes a new one (and
# the mod founds a new settlement in it) on start.
RESET=$(attr aiciv-reset-world 2>/dev/null || true)
DONE_FILE="$SERVER/.aiciv-reset-done"
if [ -n "$RESET" ] && [ "$RESET" != "$(cat "$DONE_FILE" 2>/dev/null)" ]; then
  LEVEL=$(sed -n 's/^level-name=//p' "$SERVER/server.properties" 2>/dev/null | tail -n 1)
  LEVEL=${LEVEL:-world}
  case "$LEVEL" in */*|.*|"") log "odd level-name '$LEVEL'; not resetting";; *)
    systemctl stop minecraft.service
    if [ -d "$SERVER/$LEVEL" ]; then
      OLDWORLD="$SERVER/backup-world-$(date -u +%Y%m%d-%H%M%S)"
      mv "$SERVER/$LEVEL" "$OLDWORLD"
      log "reset world $RESET: moved $LEVEL to $OLDWORLD"
    else
      log "reset world $RESET: no $LEVEL folder; a new one will be made"
    fi
    echo "$RESET" > "$DONE_FILE"
    systemctl start minecraft.service;;
  esac
fi
OLD=$(ls "$MODS"/aicivilization-*.jar 2>/dev/null)
[ "$(echo "$OLD" | grep -c .)" -le 1 ] || { log "several mod jars in $MODS; refusing"; exit 0; }

HAVE=$([ -n "$OLD" ] && sha256sum "$OLD" | cut -d' ' -f1)
if [ "$HAVE" = "$WANT" ]; then
  log "already running $WANT; nothing to do"
  exit 0
fi

TMP=$(mktemp)
curl -fsSL --retry 5 --retry-delay 3 -o "$TMP" "$URL" || { log "download failed: $URL"; rm -f "$TMP"; exit 0; }
GOT=$(sha256sum "$TMP" | cut -d' ' -f1)
[ "$GOT" = "$WANT" ] || { log "checksum mismatch: got $GOT want $WANT; keeping old jar"; rm -f "$TMP"; exit 0; }

OWNER=$(stat -c %U:%G "$MODS")
systemctl stop minecraft.service
if [ -n "$OLD" ]; then
  BACKUP="$(dirname "$MODS")/backup-aiciv-$(date -u +%Y%m%d-%H%M%S)"
  mkdir -p "$BACKUP" && mv "$OLD" "$BACKUP/" && chown -R "$OWNER" "$BACKUP"
fi
install -o "${OWNER%%:*}" -g "${OWNER##*:}" -m 644 "$TMP" "$MODS/$(basename "$URL")"
rm -f "$TMP"
systemctl start minecraft.service
log "deployed $WANT (was ${HAVE:-none}); backup in ${BACKUP:-none}"
