# AI Civilization Bedrock resource pack

A minimal Bedrock Edition resource pack defining what `aicivilization:agent`
looks like on a Bedrock client: a simple placeholder humanoid (one box for
the body, one for the head), a single solid-color texture, and idle/walk
animations. This is intentionally bare — it exists to prove agents render,
move, and show their names on Bedrock at all, not to be the final art.

Paired with the `geyser-extension/` module, which is what actually tells
Geyser to use this model for `aicivilization:agent` spawns instead of
dropping them.

## Contents

- `manifest.json` — pack header/module metadata.
- `entity/agent.entity.json` — the Bedrock client entity definition: binds
  geometry, texture, animations, and render controller to the identifier
  `aicivilization:agent` (must match the identifier the Geyser extension
  registers).
- `models/entity/agent.geo.json` — the placeholder geometry.
- `animations/agent.animation.json`, `render_controllers/agent.render_controllers.json`
  — idle/walk animation and the render controller tying it all together.
- `textures/entity/agent.png` — a single solid-color placeholder texture.

## Deploying (on the server running Geyser-Fabric)

Geyser auto-serves resource packs to connecting Bedrock clients from its
own `packs/` folder — players don't install anything manually.

1. Zip this folder's *contents* (not the folder itself as a subdirectory —
   `manifest.json` must be at the zip root) into e.g.
   `aicivilization-agents.mcpack` (a `.mcpack`/`.zip` is the same format;
   Geyser accepts either a zip or a loose folder here).
2. Drop it into Geyser's `packs/` folder.
3. Restart the server (or reload packs, if Geyser's version supports it).
4. On next connect, the Bedrock client will prompt to download/apply the
   pack automatically.

## Upgrading later

This is a placeholder. A real next step would be modeling actual
per-agent geometry/texture (e.g. in Blockbench) once the pipeline above is
confirmed working end-to-end — nothing here or in `geyser-extension/`
needs to change to support that beyond swapping these files out.
