# Prebuilt mod jar

`aicivilization-0.1.0-milestone1.jar` is the built output of this repo's
`main` branch (via `./gradlew build`), committed here purely as a
deployment convenience so a small/low-memory server (e.g. a free-tier
Compute Engine `e2-micro`, 1GB RAM) can `wget`/`curl` it directly into its
Fabric `mods/` folder instead of compiling the mod on-box — downloading
and linking against the full Minecraft + Fabric API dependency set during
a Gradle build needs more memory than a 1GB machine has.

SHA-256: `ef920d5c49f79c7c3ce287105ad8fc4b93dc16231217306b71936504ebbd35a5`

**Targets Minecraft 26.2 and requires Java 25 at runtime** (not 21) — the
mod was ported from 1.21.1 in order to use a Geyser extension that needs
Minecraft 26.2. Make sure the server's JVM is Java 25 or newer before
dropping this jar in, or the server will fail to start with an
`UnsupportedClassVersionError`.

This is a build artifact, not source — if the mod's source changes, this
file needs to be regenerated (`./gradlew build`) and replaced here.

The mod jar bundles Polymer (`polymer-core`/`polymer-common` 0.17.5+26.2),
which shows agents as villagers to clients without the mod, including
Bedrock players through Geyser. Nothing extra needs installing for it.

## Bedrock bridge files (Geyser)

Also prebuilt here for direct `wget` onto the server, same reasoning as above:

- `aicivilization-geyser-extension-0.1.0.jar` (not needed with the Polymer-enabled mod jar: Geyser never sees `aicivilization:agent`) — built from `geyser-extension/`
  (`./gradlew :geyser-extension:jar`). Goes in Geyser's `extensions/`
  folder (`config/Geyser-Fabric/extensions/`), **not** `mods/`.
  SHA-256: `5782034461743074c5fc53a4cb56d464be420307c4fe256a861d68c5f2dcc73a`
- `aicivilization-agents.mcpack` — `bedrock-resource-pack/` zipped with
  `manifest.json` at the root (README excluded). Goes in Geyser's `packs/`
  folder (`config/Geyser-Fabric/packs/`); Geyser serves it to Bedrock
  clients automatically.
  SHA-256: `aafefb82ffd03f0042850caf377a519903bc19de53014a8247ba7edb43586a9e`

Both must be regenerated if `geyser-extension/` or `bedrock-resource-pack/`
change.
