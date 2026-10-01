# AI Civilization Geyser extension

Makes `aicivilization:agent` entities visible to Bedrock Edition players
connecting through [GeyserMC](https://geysermc.org/). Bedrock has no idea
what a Fabric-registered modded entity is, so without this, Geyser silently
drops every agent spawn — the world loads fine, but no agents appear.

This is deliberately a separate, non-Fabric module (`geyser-extension/`):
Geyser extensions are plain JVM jars built against `geyser-api`, loaded by
Geyser itself from its own `extensions/` folder — not Fabric mods, and not
part of `mods/`.

## What it does

- Registers one custom Bedrock entity (`aicivilization:agent`) via Geyser's
  Custom Entity API (`GeyserDefineEntitiesEvent`).
- Redirects any Java-side spawn of `aicivilization:agent` to that custom
  definition instead of letting Geyser drop it (`ServerSpawnEntityEvent`).
- Does not touch agent minds, behavior, or anything on the Java side —
  Java clients are completely unaffected.

The visual model/texture/animations this entity uses come from
`bedrock-resource-pack/` at the repo root, which Geyser auto-serves to
connecting Bedrock clients.

## Building

```
./gradlew :geyser-extension:jar
```

Output: `geyser-extension/build/libs/aicivilization-geyser-extension-<version>.jar`.

## Deploying (on the server running Geyser-Fabric)

1. Copy the built jar into Geyser's `extensions/` folder (this is a
   subfolder Geyser-Fabric creates next to its own config, under the
   server's `config/` or run directory — not the server's `mods/`).
2. Restart the server (or the Geyser extension's own reload command, if
   enabled) to load it.
3. Confirm in the server log: a line noting the extension loaded, plus
   "Registered custom Bedrock entity for aicivilization:agent." from this
   extension's own startup log.

## Compatibility note

Geyser's Custom Entity API (`GeyserDefineEntitiesEvent`,
`ServerSpawnEntityEvent#definition`) is marked `@ApiStatus.Experimental`
by Geyser itself and can change between releases without deprecation.
This was built and compiled against `org.geysermc.geyser:api:2.11.0-SNAPSHOT`
(resolved from `https://repo.opencollab.dev/main/`). If a future Geyser
version breaks this, it will show up as a compile error here — fix is
almost always a renamed method on `JavaEntityType`, `CustomEntityDefinition`,
or `ServerSpawnEntityEvent`/`SessionSpawnEntityEvent`, not a design change.
