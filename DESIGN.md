# AI Civilization — Design

## 1. Vision

This is not a Minecraft mod with smarter NPCs. It is an AI civilization
simulator that happens to use Minecraft as its physical world.

The distinction matters. Populations of autonomous agents need agency,
continuity, memory, relationships, and institutions, and their actions need
real consequences in a shared, persistent environment. Nobody — not a
config file, not a quest script, not this mod — tells the population what
to do.

```
MINECRAFT
                 Physical universe
                        |
          +-------------+-------------+
          |                           |
      Environment                 Agents
          |                           |
   +------+------+              +-----+-----+
   |      |      |              |           |
Resources Weather Terrain    Individual   Groups
                              minds
                                  |                 |
                                  +--------+--------+
                                           |
                                           v
                                     EXPERIENCE <---------- SOCIAL
                                           |               INTERACTION
                                           v
                                        MEMORY
                                           |
                                           v
                                       DECISIONS
                                           |
                                           v
                                        ACTIONS
                                           |
                                           v
                                    WORLD CHANGES
                                           |
                                           +----------> repeat
```

The first experiment isn't "can they build a civilization?" It's: *what do
they do when nobody tells them what to do?* Milestone 1 (this codebase)
exists to answer that, honestly, before anything more ambitious is built on
top of it.

## 2. Non-negotiable architectural principles

Four decisions shape everything below. They were treated as load-bearing
from the start, not aspirational — see §9 for how each is actually enforced
in code, not just described here.

1. **Mind ≠ Minecraft entity.** A Minecraft entity is a physical embodiment
   of an agent, not the authoritative representation of its mind. Cognitive
   state (memory, relationships, goals, beliefs, reasoning) lives in a
   plain-Java `AgentMind`, owned by a world-level registry keyed by agent
   UUID, persisted independently of whether a corresponding entity is
   currently loaded. This is what will let minds later outlive entities,
   support death/rebirth and generations, be simulated offline, and scale
   to populations larger than can all be loaded as entities at once.
2. **No global or telepathic knowledge.** An agent knows something only
   because it perceived it, worked it out itself, or was explicitly told it
   by another agent. Every memory carries a provenance, and the chain is
   transitive: a told-memory records which of the *teller's* memories it
   came from, so "Elias knows about iron because Marcus told him, because
   Marcus personally found it" is reconstructable after the fact.
3. **No scripted professions, quests, or predefined civilization
   objectives.** Nothing in this codebase assigns an occupation, a role, or
   a goal to an agent. Milestone 1 exists to observe what emerges from
   needs, personality, bounded perception, memory, relationships, and basic
   reasoning — not to hand out jobs.
4. **Observability.** Every significant decision and event is recorded as
   structured data (not just prose), so "why did this agent do that?" is
   answerable after the fact, not just "what did it do?"

## 3. The agent data model

Every agent has:

```json
{
  "identity": { "name": "Elias", "birthTick": 18000 },
  "personality": { "curiosity": 0.81, "risk": 0.67, "sociability": 0.42, "ambition": 0.73 },
  "needs": { "food": 0.62, "safety": 0.81, "social": 0.31, "belonging": 0.74 },
  "beliefs": [],
  "memories": [],
  "relationships": [],
  "goals": [],
  "possessions": []
}
```

But almost none of this is filled out by hand. `identity` and `personality`
are rolled once at creation (`AgentMind`, `Identity`, `Personality`) and
never mutated. Everything else — `beliefs`, `memories`, `relationships`,
`goals`, `possessions` — starts empty and is built up entirely by the
agent's own experience:

- **Needs** (`Needs`) decay continuously and are pushed back up only by the
  agent's own actions (foraging, finding shelter, socializing).
- **Memories** (`MemoryStream`, `MemoryEntry`) are episodic: each has a
  tick, a description, an importance, and a `Provenance`
  (`Perceived` / `Inferred` / `Told`). Retrieval ranks by a blend of
  recency and importance (a simplified version of the Park et al.
  "generative agents" retrieval scheme).
- **Relationships** (`Relationships`, `RelationshipData`) track affinity
  and trust toward specific other agents, updated only by things that
  actually happened between them.
- **Beliefs** (`Belief`) and **Goals** (`Goal`) are formed only by the
  agent's own reasoning (`AgentMind.formBelief` / `AgentMind.addGoal`),
  called only from the fast utility layer or the deep reasoning layer —
  never seeded by the simulation.
- **Possessions** (`Possession`) are a plain list; picking things up/using
  them is minimal in Milestone 1 (see §10).
- **Knowledge** (`Knowledge`) is deliberately *not* a separately-written
  field: it's a read-through summary computed from the memory stream on
  demand, so it can never drift out of sync with what the agent actually
  experienced. A real consolidation/decay model, where knowledge outlives
  the memory that produced it, is future work — this is the seam it
  attaches to.

Roles are never assigned. If, after running this for a while, one agent's
memory/goal history looks like "the one who kept finding iron," that's a
description a human observer applies afterward — never a field the
simulation set.

## 4. Dual-process architecture

```
Agent
                   |
          +--------+--------+
          |                 |
     Fast system        Deep system
   (utility AI,        (ReasoningProvider,
    every tick,         throttled + novelty-
    no LLM)             gated LLM call)
          |                 |
     NeedsDrivenGoal   HeuristicReasoningProvider
     ConversationBehavior  or AnthropicReasoningProvider
```

**Fast system** (`com.aicivilization.behavior`): a single custom
`net.minecraft.entity.ai.goal.Goal` (`NeedsDrivenGoal`) that runs every
tick. It perceives the world (`PerceptionSystem`), asks the agent's mind to
score the intents that are currently physically possible
(`AgentMind.decide`), and drives the chosen one — pathfinding, "foraging",
fleeing, approaching another agent to talk, exploring, or resting. No LLM
involved; this is a plain utility AI (needs + personality + a bit of
controlled randomness), the same kind of system that drives moment-to-
moment survival behavior in any life-sim.

**Deep system** (`com.aicivilization.reasoning`): a `ReasoningProvider`
invoked far less often — on a routine interval, or sooner if the agent hits
a need crisis (`ReasoningScheduler`) — that can suggest a new goal and/or a
new belief. `AgentContext`, the only input a provider ever receives, is
built strictly from *that agent's own* memories/needs/personality/goals —
never from world state or another agent's mind — so the epistemic rule
holds even when the reasoning is happening off-thread or over the network.

- `HeuristicReasoningProvider` (default, zero network calls): weighs needs,
  personality, and controlled randomness together via softmax sampling —
  deliberately *not* an `if trait > threshold` rule table, which would
  quietly become a disguised profession system where the same personality
  always produces the same "role."
- `AnthropicReasoningProvider`: a real client against the Claude Messages
  API (`java.net.http.HttpClient`), off the server thread, results applied
  back via `MinecraftServer.execute`. Falls back to the heuristic provider
  (logging a warning) on any missing config or failure, rather than ever
  crashing the server. Configured in `config/aicivilization.json`
  (`llmProvider`, `anthropicApiKeyEnv`, `anthropicModel`) — the API key
  itself is read from an environment variable, never written to disk by
  this mod.

## 5. Mind vs. entity

```
                    PopulationRegistry (per world, persisted)
                              |
                +-------------+-------------+
                |                           |
            AgentMind A                 AgentMind B
        (memory, needs,              (memory, needs,
         goals, beliefs,              goals, beliefs,
         relationships)               relationships)
                |                           |
          AgentEntity                (no entity loaded —
        (thin embodiment,             mind persists; future:
         reads/writes the             offline simulation,
         mind, has no data            death/rebirth)
         of its own)
```

`AgentEntity extends PathAwareEntity` is deliberately thin: on first tick it
looks up (or creates) its `AgentMind` in the world's `PopulationRegistry`,
keyed by its own entity UUID — which Minecraft already persists across
save/reload, so that's the entire linkage needed for a mind to survive a
restart. The entity runs perception against itself and asks the mind what
it wants; the mind has no idea a Minecraft entity exists.

`com.aicivilization.mind` and the core of `com.aicivilization.population`
(the `Population` class) have **zero imports from Minecraft**, checked by
an automated test (§9), not just a comment. `PopulationRegistry` is the one
class allowed to know about Minecraft's `PersistentState`/NBT — it's the
adapter, not the model.

## 6. Perception and the epistemic boundary

```
Minecraft world
       |
       v
PerceptionSystem  (bounded radius: nearby animals, hostiles,
       |            players, other agents)
       v
 Surroundings  (what's physically POSSIBLE right now)
       |
       v
AgentMind.decide(tick, availableIntents)  (what's WANTED)
```

`PerceptionSystem` is the only system allowed to originate a
`Provenance.Perceived` memory. It never hands an `AgentMind` raw Minecraft
objects — only primitives (`String`, `double`, `UUID`) already extracted
from the world. Concretely, **no method anywhere in `com.aicivilization.mind`
accepts** a Minecraft `World`/`ServerWorld`/`MinecraftServer`, an
`EventLog`, a `PopulationRegistry`, or another `AgentMind`. That's not a
convention — `AgentMindEpistemicBoundaryTest` asserts it by reflection over
every method and constructor in the package, and fails the build if it's
ever violated.

Two agents standing next to each other do not share information by default.
`ConversationBehavior` is the only other path a fact can take between
minds: proximity only makes an interaction *possible* — each mind then runs
a small social-utility evaluation (using its own needs, personality, and
relationship-toward-that-specific-agent) to decide whether to interact and
for what purpose (exchange information, socialize, request help, offer a
trade, or avoid). Only "exchange information" and "socialize" have a real
effect in Milestone 1; the others are recognized outcomes of the same
evaluation, logged but not yet acted on — the extension point for
trade/cooperation.

When information *is* exchanged, the listener's mind does **not** receive a
copy of the teller's memory. It creates a brand-new `MemoryEntry`, with its
own id and timestamp, described as "X told me: ..." and tagged
`Provenance.Told(tellerId, tellerMemoryId)`. The chain is preserved
transitively — the told-memory points at the teller's specific source
memory, whose own provenance might be `Perceived` or a further `Told` — so
walking it back can (in principle) reconstruct how far a piece of
information has traveled and through whom.

## 7. Structured observability

```
SimEvent (typed: tick, type, subjects, summary, typed Cause list)
       |
       v
   EventLog (append-only, per world, never read by agents)
       |
   +---+-------------------------+
   |                             |
/civ why <agent>            WorldChronicle (derived, narrative
(walks the Cause chain       subset of events — /civ history)
 behind a decision)
```

`SimEvent` records are structured, not prose: a `tick`, an `EventType`
(`SPAWN`, `DECISION`, `TOLD`, `CONVERSATION`, `REASONING_INVOKED`,
`REASONING_RESULT`, `NEED_CRISIS`, `DEATH`), the agent(s) involved, a
human-readable summary, and a list of typed `Cause`s (`{sourceType: EVENT |
MEMORY | PERCEPTION | NEED_STATE, sourceId, detail}`). `AgentMind.decide`
already produces this level of detail per decision via `DecisionTrace`
(every candidate intent considered, its score, and the factors that
produced that score) — `NeedsDrivenGoal` is what turns a `DecisionTrace`
into a logged `SimEvent`.

`EventLog` is the machine-readable record; agents never read it (that would
be exactly the kind of global knowledge §2 rules out). `WorldChronicle` is
a human-readable narrative *derived from* the subset of events that are
narratively significant (spawns, conversations, need crises, deaths) — not
a separately hand-authored log, so it can't drift from what actually
happened.

`/civ why <agent>` walks an agent's most recent `DecisionTrace` — the
candidates it weighed, their scores and factor breakdowns, the causes
behind the winning choice — plus its recent logged events. That's the
direct, concrete answer to "why did this agent do that?", not a guess.

## 8. Emergent economy, culture, and history — the intended shape

None of this is implemented yet; it's the reason the architecture above
looks the way it does. With agents that have different capabilities,
information, and incentives, and no assigned roles, the intended emergent
chain looks like:

```
A discovers iron (perceived)
       |
       v
B learns about iron (told by A)
       |
       v
C forms a belief about how to use it (inferred, via reasoning)
       |
       v
D forms a goal around tools (goal, related intent = FORAGE_FOOD/EXPLORE-like)
       |
       v
E starts trading (once OFFER_TRADE has a real effect — future work)
```

Nobody is programmed with "create an economy." If it happens, it happens
because information (via `Told` memories), needs, and personality produced
different incentives for different agents. `WorldChronicle` is what would
let a human read that history back afterward — "Day 7: Marcus discovered
iron. Day 12: the first stone bridge was built." — without it having been
scripted anywhere.

## 9. How each principle is actually enforced (not just documented)

| Principle | Enforcement |
|---|---|
| Mind ≠ entity | `mind`/`population` packages contain no `net.minecraft.*` imports except in `PopulationRegistry`/`AgentMindNbt`, which exist purely as the persistence adapter. `AgentEntity` holds no state of its own beyond a cached `AgentMind` reference. |
| No global/telepathic knowledge | Every `MemoryEntry` has a non-optional `Provenance`. `AgentMindEpistemicBoundaryTest` reflects over every method/constructor in `com.aicivilization.mind` and fails if any accepts a Minecraft world/server type, `EventLog`, `PopulationRegistry`, or another `AgentMind`. |
| No scripted roles | No field in `AgentMind` is settable from outside except through `perceive`/`receiveTold`/`inferMemory`/`formBelief`/`addGoal`/`addPossession` — all of which require the caller to already have done the corresponding perception/communication/reasoning. There is no `setOccupation` or equivalent anywhere in the codebase. |
| Observability | `DecisionTrace` and `SimEvent` are structured, typed records, not strings assembled ad hoc; `/civ why` and `/civ history` are built directly from them. |

## 10. Milestone 1 — what this codebase actually delivers vs. defers

**Delivered:**
- `AgentMind` with identity, personality, needs, memory stream (with
  provenance), relationships, beliefs, goals, possessions.
- A fast, needs-driven utility AI (no LLM) that forages, flees danger,
  socializes, explores, or rests, and logs a structured decision trace for
  every choice.
- A pluggable deep-reasoning layer: a working no-network heuristic
  provider by default, and a real Anthropic Claude API client the user can
  turn on by setting an API key and a model id.
- Proximity-gated, purpose-selecting conversations that create new,
  independently-provenanced memories rather than sharing state between
  minds.
- A structured, queryable event log and a narrative chronicle derived from
  it, both persisted across world save/reload, plus `/civ spawn`,
  `/civ inspect`, `/civ history`, and `/civ why` commands to observe all of
  the above from outside the simulation.
- A `Population`/`PopulationRegistry`/`AgentScheduler` split that already
  separates "every mind that has ever existed" from "which entities are
  currently loaded," and an `ActivityTier` enum (`ACTIVE`/`IDLE`/
  `DORMANT`/`DEAD`) — even though with a handful of agents everything is
  effectively `ACTIVE` today.
- An automated JUnit suite covering memory/provenance behavior, decision
  scoring, population lifecycle, and — most importantly — the epistemic
  boundary itself, so principle #2 can't silently regress.

**Explicitly deferred (not started, and not faked):**
- **Generations, death → rebirth, inheritance.** `PopulationRegistry`
  already keeps a dead agent's mind around (for exactly this reason), but
  nothing spawns an heir or transfers property/beliefs/relationships yet.
- **Culture** — traditions, language drift, architecture, law, mythology.
  Beliefs can form, but nothing yet aggregates them across a population
  into a shared culture.
- **A real economy.** `OFFER_TRADE` is a recognized conversational purpose
  that's logged but has no mechanical effect (no item transfer, no
  bartering logic) yet.
- **Population scheduling beyond the seam.** `AgentScheduler` and
  `ActivityTier` exist and are wired into the fast-system tick, but only
  `ACTIVE` is ever actually used today — proximity/importance-based tiering
  for hundreds-to-thousands of agents is future work, not implemented.
- **Distributed/multi-region scaling**, and **treating a human player as
  an ordinary perceived actor with no special knowledge** (today,
  `PerceptionSystem` does perceive nearby players and an agent can approach
  and "meet" one, but there's no belief-formation or memory content yet
  that specifically reasons about a player differently from any other
  entity — which is arguably already the right default, just not
  exercised much yet).
- **Real physical action fidelity.** "Foraging" currently means walking to
  a nearby animal and simulating finding food (a need/memory effect, no
  actual eating/inventory/crafting); mining, building, and item use are not
  implemented. This keeps Milestone 1's scope to the question the brief
  actually asks first — "what do they do when nobody tells them what to
  do?" — without also having to get survival mechanics fully right.
- **Custom visuals.** Agents render using the vanilla zombie model/texture
  (`AgentRenderer`) purely so they're visible without needing new art
  assets. A distinct look is cosmetic, not a milestone concern.

**Build verification performed:** `./gradlew build` (compiles `main`,
`client`, and `test` source sets against the real, Loom-decompiled and
Yarn-remapped Minecraft 1.21.1 jar, and produces the remapped mod jar) and
`./gradlew test` both pass. This is a genuine compiler-verified check
against real Mojang-mapped APIs, not just code that "looks right." What
was **not** done in this environment: launching a client or dedicated
server and actually playing with spawned agents — that requires a
graphical/interactive session this environment doesn't have. Everything
above should be read as "compiles and is internally consistent," not as
"has been played."
