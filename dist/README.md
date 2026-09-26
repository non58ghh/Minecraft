# Prebuilt mod jar

`aicivilization-0.1.0-milestone1.jar` is the built output of this repo's
`main` branch (via `./gradlew build`), committed here purely as a
deployment convenience so a small/low-memory server (e.g. a free-tier
Compute Engine `e2-micro`, 1GB RAM) can `wget`/`curl` it directly into its
Fabric `mods/` folder instead of compiling the mod on-box — Loom's
Minecraft decompile/remap step during a full Gradle build needs more
memory than a 1GB machine has.

SHA-256: `0c2b269d308eca4a162eecbfba727c91dd3d8aa9aafcf2309b770c4a12fbf16a`

This is a build artifact, not source — if the mod's source changes, this
file needs to be regenerated (`./gradlew build`) and replaced here.
