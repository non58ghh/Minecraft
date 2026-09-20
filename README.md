# AI Civilization

This project does not attempt to make NPCs behave intelligently according
to a predefined script. It attempts to create conditions under which
autonomous behavior, social structures, specialization, and eventually
civilization can emerge from interacting agents — using Minecraft as the
physical world those agents live in, not as the thing being modded to have
smarter monsters.

See **[DESIGN.md](DESIGN.md)** for the full architecture, the reasoning
behind it, and an honest breakdown of what's implemented versus deferred.

## What's here right now (Milestone 1)

- Autonomous agents (`/civ spawn <count>`) with their own persistent minds:
  personality, needs, episodic memory (with provenance — an agent only
  knows what it perceived, worked out, or was told), relationships, beliefs,
  and goals.
- A fast, needs-driven utility AI (no LLM) driving moment-to-moment survival
  behavior, plus a pluggable "deep reasoning" layer that can call a real
  Claude API for higher-level goal/belief formation.
- No scripted professions, quests, or assigned roles anywhere.
- A structured, queryable event log and a derived world chronicle, both
  persisted across save/reload.
- Debug/observation commands: `/civ spawn <count>`, `/civ inspect <name>`,
  `/civ history`, `/civ why <name>`.

## Requirements

- Java 21
- Minecraft 1.21.1 (downloaded automatically by the build)

## Building and testing

```
./gradlew build   # compiles main + client + test, runs tests, builds the mod jar
./gradlew test    # just the JUnit test suite
```

The built mod jar ends up at `build/libs/aicivilization-<version>.jar`.

## Running it locally

```
./gradlew runServer   # or: ./gradlew runClient
```

Once in a world:

```
/civ spawn 10
/civ inspect Elias
/civ history
/civ why Elias
```

## Enabling real LLM-backed reasoning

By default, higher-level reasoning uses a local heuristic (no network
calls). To use a real Claude model instead, edit
`config/aicivilization.json` (created on first run) in your run/instance
directory:

```json
{
  "llmProvider": "anthropic",
  "anthropicApiKeyEnv": "ANTHROPIC_API_KEY",
  "anthropicModel": "<a current Claude model id — see https://docs.anthropic.com/en/docs/about-claude/models>",
  "anthropicMaxTokens": 300,
  "reasoningIntervalTicks": 6000
}
```

Then set the `ANTHROPIC_API_KEY` environment variable before launching the
server/client. The key itself is never written to disk by this mod — only
the name of the environment variable to read it from is. If the key or
model is missing, or a call fails, agents automatically fall back to the
heuristic provider and a warning is logged; the server never crashes over
this.

## Status

Compiled and internally consistent against the real, Loom-remapped
Minecraft 1.21.1 API, with a JUnit suite covering memory/provenance
behavior, decision scoring, and the epistemic boundary itself. Not yet
manually played through in a graphical client/server session — see
DESIGN.md §10 for exactly what's implemented versus deferred.
