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
  `/civ history`, `/civ why <name>`, and `/civ off` / `/civ on` / `/civ status`
  (see "Switching it off").

## Requirements

- Java 25
- Minecraft 26.2 (downloaded automatically by the build)

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
  "anthropicMaxTokens": 150,
  "reasoningIntervalTicks": 6000,
  "reasoningCrisisCooldownTicks": 1200,
  "reasoningNoveltyThreshold": 0.1,
  "maxReasoningCallsPerAgentPerDay": 200
}
```

Each reasoning pass is one API call, so these settings control cost. An
agent reflects at most every `reasoningIntervalTicks`, and only if it has a
new memory or a need moved by `reasoningNoveltyThreshold` since its last
pass. It also thinks once on entering a need crisis (or when a different
need becomes the critical one), with crisis passes at least
`reasoningCrisisCooldownTicks` apart. Agents without a loaded body don't
reason. `maxReasoningCallsPerAgentPerDay` caps passes per agent per
real-time day (0 = no cap); past it the agent keeps going on its built-in
fast system. Moving, eating, fleeing and building never need the LLM, so
fewer passes don't make agents worse at surviving.

Then set the `ANTHROPIC_API_KEY` environment variable before launching the
server/client. The key itself is never written to disk by this mod — only
the name of the environment variable to read it from is. If the key or
model is missing, or a call fails, agents automatically fall back to the
heuristic provider and a warning is logged; the server never crashes over
this.

## Switching it off

`/civ off` (operators only) freezes every agent and stops all reasoning,
so no LLM API calls are made; `/civ on` resumes. `/civ status` shows which
it is. The setting is saved as `simulationEnabled` in
`config/aicivilization.json`, so it survives restarts. The Minecraft server
and the observer page keep running either way; to stop everything,
including compute billing, stop the VM itself.

## Watching it: the observer page

The mod serves a read-only web page for watching the simulation from a
phone or browser: an overview with the chronicle, a card per agent (needs,
current activity, top goal, position), each agent's goals, beliefs,
relationships and memories with where each came from, a "Why" panel
showing every option behind the agent's latest decision and what scored
it, and a timeline where causes link back to the events and memories
behind them.

It starts with the server on port `8080`. On first start the mod writes a
random `observerToken` to `config/aicivilization.json` and logs the link:

```
AI Civilization observer listening on port 8080. Open http://<server address>:8080/?t=<token>
```

Every `/api/` request needs that token, so keep the link private. The
page and API only read the simulation; they never change it. Settings in
`config/aicivilization.json`: `observerEnabled` (default `true`),
`observerPort` (default `8080`), `observerToken`. On a cloud VM, open the
port in the firewall (for example a rule allowing TCP 8080).

## Playing from Bedrock Edition (mobile) via Geyser

Bedrock Edition (the mobile/console app) can't connect to a Java server
directly. [GeyserMC](https://geysermc.org/) bridges the two protocols; it
installs as a second mod jar (`geyser-fabric-*.jar`) in the same `mods/`
folder as this one, on the same server — no separate proxy process needed.

Geyser can only translate vanilla entity types, so the mod uses
[Polymer](https://modrinth.com/mod/polymer) (bundled inside the mod jar)
to tell any client without the mod that each agent is a villager. Bedrock
players see agents as villagers with their name tags; the server-side
entity and its mind are unchanged. Install
[Floodgate](https://modrinth.com/mod/floodgate) alongside Geyser (with
Geyser's `auth-type: floodgate`) to let Bedrock players join without a
Java account.

## Status

Compiled and internally consistent against the real, official-mappings
Minecraft 26.2 API, with a JUnit suite covering memory/provenance
behavior, decision scoring, and the epistemic boundary itself. Not yet
manually played through in a graphical client/server session — see
DESIGN.md §10 for exactly what's implemented versus deferred.
