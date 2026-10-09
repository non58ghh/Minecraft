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
  `/civ history`, `/civ why <name>`, `/civ give <name> <item> <count>`
  (operators; e.g. `/civ give Iris bread 6`), and `/civ off` / `/civ on` / `/civ status`
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
  "maxReasoningCallsPerAgentPerDay": 200,
  "dialogueIntervalTicks": 2400
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

Conversations that make the timeline are written out by the same model: a
few lines of real dialogue, grounded in what the two agents know (their
needs, home, recent experiences and how they feel about each other), and
each remembers the gist. That's one call per written conversation, at most
one every `dialogueIntervalTicks` across the whole population (2400 ticks,
two minutes, by default; 0 turns it off). The observer shows the lines on
the event's page. Without an LLM, conversations still happen; the event
just lists what each brought up.

Then set the `ANTHROPIC_API_KEY` environment variable before launching the
server/client. The key itself is never written to disk by this mod — only
the name of the environment variable to read it from is. If the key or
model is missing, or a call fails, agents automatically fall back to the
heuristic provider and a warning is logged; the server never crashes over
this.

## Food, farming and starvation

Agents hunt livestock, pick ripe berries and crops, and, when their
searches for food keep failing, farm: they collect seeds from grass, dig
farmland (near water when they can, where crops grow faster), plant it,
come back to tend it, harvest it, and bake bread from wheat. They also feed
pairs of animals so they breed. A harvested crop is replanted when the agent
has the seed, so village farms aren't stripped.

A full stomach lasts about 33,000 ticks (a day and a third) without
eating. With nothing in its stomach an agent loses one point of health (of 20)
every `starvationDamageIntervalTicks` (default 12000 ticks, 10 minutes of
running time, so about 3 hours 20 minutes from empty to dead) and a
well-fed agent slowly heals. Set it to 0 to turn starvation off.

## Homes and designs

Each agent imagines its own home the first time it sets about gathering
wood or building: one Claude call per agent, ever, with its personality and
recent memories, answered as a small layered drawing (`#` plank, `.` inside,
`D` doorway). The drawing is checked (size, walls all round, a roof, a way
in from the door) and anything that doesn't hold up, or any agent without
Claude configured, gets a design drawn procedurally from its personality
instead: ambitious agents build bigger, curious ones taller. Until a design
arrives an agent can always build the 3x3 hut it knows from the start.
Logs are split into four planks as they're placed, as at a crafting table.

The first building an agent finishes becomes its home. At night it goes
back and sleeps there (unless it is starving), resting at home restores
safety and belonging three times faster, and it farms near home. Coming home
it checks the building: missing blocks get repaired, and a home with more
than a third of it gone is mourned and given up. The observer's agent page
shows the home and every design the agent knows, with where each idea came
from.

### Designs spread, and homes built together

A design can catch on. An agent near someone's standing home may study it
and learn to build one like it, usually not quite the same (a bit wider,
taller, the door moved); an agent may also describe its home in conversation
to one who has none, which loses even more in the telling. Whether an agent
then builds a borrowed design instead of its own depends on how much it
trusts and likes whoever it came from. The observer shows each agent's
designs and where each came from ("copied after seeing Edda's", "heard about
it from Brenna").

Two agents without a home who trust each other may agree, in conversation,
to build one together: the house one of them already has going up, or the
proposer's design at whichever site one of them finds first (the other
hears where when they next meet). Both put blocks on it; when it's finished
it's home for both, and having built it together deepens their trust. A
shared building that never comes together is given up after a few days.

Houses on uneven ground get a plank foundation under any one-block dips (up
to a third of the floor), and a house part-way up is remembered across a
server restart rather than abandoned.

## Tools, trade and help

Agents craft from what they carry, the way a player does: logs into
planks, planks into sticks, then a wooden axe, pickaxe, sword and hoe
(keeping enough wood back for a first home), and stone ones once they have
cobblestone, which a pickaxe gets from exposed stone or from digging out of
a cave. They hold the right tool for the job, and tools wear out and break.
An axe brings down more of a tree at a time, a pickaxe digs stone fast and
keeps the cobblestone, a sword hits harder when hunting, a hoe makes tending
crops go further.

An agent with a home and eight cobblestone sets up a furnace by its door and
cooks raw meat (and potatoes) there with coal or wood; cooked food is worth
two to three times as much, so raw meat is kept for the furnace unless the
agent is getting desperate. Agents notice how much they already carry: a
full larder takes the urgency out of farming, plenty of wood with a home
built stops the chopping, and a field of about thirty plants is left at
that rather than growing forever. Ripe crops within reach are brought in
together, replanting as they go, and seeds go in a few plots at a time.

When two agents meet they may share news, pass the time, trade, or ask for
help. A well-stocked agent that meets a hungry one offers food unasked. Each values things by its own situation (food is worth most to the
hungry, wood to the homeless, a missing tool a lot), and a trade only
happens if both come out ahead, with a little slack for someone trusted.
An agent in need asks for food; whether it gets some depends on whether the
other can spare it and how sociable, fond or trusting they are. News is
first-hand only and never told to the same listener twice, and the same pair
doesn't strike up a conversation more than once every half minute.

## Getting unstuck

Going underground is fine with a reason: an agent with a pickaxe that sees
coal on a cave wall while it's short of fuel, or stone while it still lacks
stone tools, is drawn to go and mine it, and stays down as long as it's
working. (Iron and copper have no use yet, so they're left in the rock.) An agent that is
underground with nothing to do there (rock or earth overhead, no sky) for a
while looks for a way to walk out; if there isn't one, it digs a
staircase up toward daylight, fast with a pickaxe and slowly by hand. It
only digs natural ground, turns away from water, lava and sand or gravel
that would fall on it, and climbs out if it is ever buried. Only a real climb (a few steps or more)
makes the timeline, and a cave mouth it just got out of is left again
quickly if it wanders back under. A place an agent found it couldn't get to (ore inside a cave below, a
field across water, a home up a cliff) is left alone for a while instead of
tried again and again. Routes that would run through a cave are avoided
when choosing where to wander. A lonely agent with nobody in sight goes to
where it last saw someone it likes. Agents never path into water (they'd walk off a bank into the sea and not
climb back out) or onto powder snow, swim up rather than sink if they do end up in it, and head
for the nearest shore. One that keeps wanting to go somewhere but hasn't
moved two blocks in half a minute, say stranded on a peak or stuck in a pit,
scrambles to the nearest open dry ground within six blocks (down a cliff,
or up to three blocks out of a hole). One down a narrow hole cuts steps
out straight away, even if it had nowhere in mind; if that doesn't help it tries less
and less often. An agent that keeps getting stuck may be cut off: it will swim
across water to land on the far side, or cut steps up a hillside to get out
of a strip at the foot of a cliff. Wherever it got stuck is avoided for a
while, and when choosing where to wander it picks somewhere it can actually
get to rather than a spot beyond a cliff. Every agent gets a name no other agent has had.

## Active hours

To run agents only part of the day, set a daily window in
`config/aicivilization.json`:

```json
{
  "activeHoursStart": "18:00",
  "activeHoursEnd": "00:00",
  "activeHoursTimeZone": "America/New_York"
}
```

Outside the window agents rest exactly as after `/civ off`: they stand
still and no AI calls are made. The window may cross midnight; leave the
times blank to run all day. `/civ status` and the observer page show the
window. While agents run, the mod keeps the chunk each agent's body is in
loaded (vanilla forced chunks), so they act with nobody online; outside
running time it releases those chunks. Recent Minecraft versions also
pause a server with no players online, which freezes agents; set
`pause-when-empty-seconds=-1` in `server.properties` if they should run
with nobody connected.

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
`observerPort` (default `8080`), `observerToken`, and `observerRequireToken`
(default `true`). Set `observerRequireToken` to `false` to open the page
without a token; then anyone who can reach the port can watch the
simulation. On a cloud VM, open the port in the firewall (for example a
rule allowing TCP 8080).

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
