# Prebuilt mod jar

`aicivilization-0.1.0-milestone1.jar` is the built output of this repo's
`main` branch (via `./gradlew build`), committed here purely as a
deployment convenience so a small/low-memory server (e.g. a free-tier
Compute Engine `e2-micro`, 1GB RAM) can `wget`/`curl` it directly into its
Fabric `mods/` folder instead of compiling the mod on-box — downloading
and linking against the full Minecraft + Fabric API dependency set during
a Gradle build needs more memory than a 1GB machine has.

SHA-256: `ef23ddd7a3429243320094dbc377fb4858ee61f4bfc41d468c22b513564a590d`

**Targets Minecraft 26.2 and requires Java 25 at runtime** (not 21) — the
mod was ported from 1.21.1 in order to use a Geyser extension that needs
Minecraft 26.2. Make sure the server's JVM is Java 25 or newer before
dropping this jar in, or the server will fail to start with an
`UnsupportedClassVersionError`.

This is a build artifact, not source — if the mod's source changes, this
file needs to be regenerated (`./gradlew build`) and replaced here.

# Prebuilt Geyser extension + resource pack

`aicivilization-geyser-extension-0.1.0.jar` is the built output of
`geyser-extension/` (`./gradlew :geyser-extension:jar`) — drop it in
Geyser's `extensions/` folder (not `mods/`).

SHA-256: `5782034461743074c5fc53a4cb56d464be420307c4fe256a861d68c5f2dcc73a`

`aicivilization-agents.mcpack` is `bedrock-resource-pack/` zipped with
`manifest.json` at the archive root — drop it in Geyser's `packs/`
folder. See `geyser-extension/README.md` and
`bedrock-resource-pack/README.md` for what each one does and where
Geyser's `extensions/`/`packs/` folders actually live relative to its
config.

SHA-256: `9eba48e0e556a402f995d0eba64826547f5bf70bb35848262104519b8adfcd6b`
