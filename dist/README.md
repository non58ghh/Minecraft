# Prebuilt mod jar

`aicivilization-0.1.0-milestone1.jar` is the built output of this repo's
`main` branch (via `./gradlew build`), committed here purely as a
deployment convenience so a small/low-memory server (e.g. a free-tier
Compute Engine `e2-micro`, 1GB RAM) can `wget`/`curl` it directly into its
Fabric `mods/` folder instead of compiling the mod on-box — downloading
and linking against the full Minecraft + Fabric API dependency set during
a Gradle build needs more memory than a 1GB machine has.

SHA-256: `bd4327b750d0f6a297d2dd38859075454119b740e0b0715ea744b46736862690`

**Targets Minecraft 26.2 and requires Java 25 at runtime** (not 21) — the
mod was ported from 1.21.1 so it can run with a current Geyser build,
which needs Minecraft 26.2. Make sure the server's JVM is Java 25 or newer before
dropping this jar in, or the server will fail to start with an
`UnsupportedClassVersionError`.

This is a build artifact, not source — if the mod's source changes, this
file needs to be regenerated (`./gradlew build`) and replaced here.

The mod jar bundles Polymer (`polymer-core`/`polymer-common` 0.17.5+26.2),
which shows agents as villagers to clients without the mod, including
Bedrock players through Geyser. Nothing extra needs installing for it.
