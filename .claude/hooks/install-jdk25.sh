#!/bin/bash
# SessionStart hook: the mod targets Java 25, but cloud containers ship Java 21.
# Installs a Temurin 25 JDK once per container and puts it on PATH.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

JDK_ROOT="$HOME/.jdk25"
if ! ls "$JDK_ROOT"/jdk-25*/bin/java >/dev/null 2>&1; then
  mkdir -p "$JDK_ROOT"
  curl -fsSL 'https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse' \
    | tar xz -C "$JDK_ROOT"
fi
JAVA_HOME_25=$(ls -d "$JDK_ROOT"/jdk-25* | head -1)

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export JAVA_HOME=\"$JAVA_HOME_25\"" >> "$CLAUDE_ENV_FILE"
  echo "export PATH=\"$JAVA_HOME_25/bin:\$PATH\"" >> "$CLAUDE_ENV_FILE"
fi
