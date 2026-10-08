---
name: ship-jar
description: Rebuild the mod, run the tests, and refresh the prebuilt jar in dist/ (plus its SHA-256 in dist/README.md). Use after any change under src/ that should reach the live server, or when asked to "build", "rebuild the jar", "update dist", or "ship it".
---

# Ship the jar

`dist/aicivilization-<version>.jar` is committed so a 1GB server can download
it instead of compiling. Every source change that ships must regenerate it,
in the **same commit** as the source change.

## Steps

1. Make sure Java 25 is active (`java -version`). In cloud sessions the
   SessionStart hook (`.claude/hooks/install-jdk25.sh`) installs it. If
   `java -version` still says 21, fetch Temurin 25 by hand:
   ```
   curl -fsSL 'https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse' | tar xz -C <dir>
   export JAVA_HOME=<dir>/jdk-25* PATH=$JAVA_HOME/bin:$PATH
   ```
2. Build and test:
   ```
   ./gradlew build --no-daemon
   ```
   This compiles main + client + test and runs the JUnit suite. If it fails,
   fix the cause; never skip or disable a test. A `429 Too Many Requests`
   from repo.maven.apache.org is a transient rate limit, not a code problem:
   wait and retry with backoff (up to ~4 times) before reporting it.
3. Copy the jar (version from `gradle.properties` -> `mod_version`):
   ```
   v=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
   cp build/libs/aicivilization-$v.jar dist/aicivilization-$v.jar
   ```
   Use the plain jar, not `-sources.jar`.
4. Update the `SHA-256:` line in `dist/README.md`:
   ```
   sum=$(sha256sum dist/aicivilization-$v.jar | cut -d' ' -f1)
   sed -i "s/^SHA-256: \`[0-9a-f]*\`/SHA-256: \`$sum\`/" dist/README.md
   ```
5. Verify: `grep SHA-256 dist/README.md` matches `sha256sum dist/*.jar`, and
   `unzip -l dist/*.jar | grep fabric.mod.json` shows the mod metadata.
6. Stage `dist/` together with the source change. Report the jar size and
   the new checksum.

If `mod_version` changed, rename the jar in `dist/` and update every mention
of the old filename (`dist/README.md`, `README.md`).
