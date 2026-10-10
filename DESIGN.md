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
   because it perceived it, worked it out itself, was explicitly told it
   by another agent, or read it on a sign. Every memory carries a
   provenance, and the chain is transitive: a told-memory records which of
   the *teller's* memories it came from, so "Elias knows about iron because
   Marcus told him, because Marcus personally found it" is reconstructable
   after the fact. A read-memory does the same through the writing, back to
   the writer's memory, even after the writer is gone.
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
- Recipe knowledge (open-ended play, phase 1 stage 1): each agent has a
  `RecipeBook` of recipes and material sources, every entry tagged with how
  it was learned (start, made, saw, told; told is only a hint until made).
  `world/RecipeCatalog` reads the game's own crafting and smelting recipes at
  start and on reload; it checks things and seeds new books
  (`world/StartingKnowledge`) but agents never plan from it. The epistemic
  boundary test now scans every class in `mind` (fields too), and trade
  offers come only from what the other agent says it would part with.
- Plans (open-ended play, phase 1): goals may carry a target (item, count),
  validated against `world/RecipeCatalog`; `mind/Planner` backward-chains
  over the agent's own `RecipeBook` only (tools/stations held, not used up;
  smelting needs coal; unknown = a named gap). `PURSUE_PLAN` re-plans from
  the pack whenever chosen, so interruptions lose nothing.
  `behavior/PlanRunner` runs the first step with `action/Verbs` (craft at a
  real table, smelt in a real furnace held via `world/StationHolds`) or
  `behavior/DigDown` (a staircase down for ore or stone, ended by cave
  escape). Gate run (fresh world, Claude goals, 4 game days): 12/12 alive,
  P99 tick 6-10 ms; iron made from a plan with stand-in goals, Claude chose
  shelter and food over iron in that time.
- Goals finish: acting on a goal's intent three times sets it down, so a
  reflection like "get to know the neighbours" no longer keeps an agent
  socialising forever. That is effort, not success (the goal's words aren't
  acted on literally), so it's remembered as "I spent time on: ..." below
  news importance and logged as work, not a milestone. Only goals with a
  target item finish by being met. A new target doesn't cancel a different
  one in progress; one pushed out by the active-goal cap is remembered as
  set aside.
- The reasoning prompt (`AgentContext`) is in sections of plain words:
  the world now (day, time, night warning), who you are, how you're doing
  (needs with what bears on them), where you are and who's in sight
  (`AgentContext.Situation`, perceived where it stands, built only when
  it thinks), people you know (relationships, closest first), what's been
  happening (notable memories, chores folded per day), what you're
  working on (goals with how long and how far, home progress, setbacks),
  what each activity does, and a reply that names the activity first and
  phrases the goal as what it will really do.
- Reasoning failures (API status, empty, cut-off or unreadable replies) are
  `REASONING_FAILED` events with the reason, not silent empty passes;
  reasoning gets at least 300 output tokens. Beliefs carry `Inferred`
  provenance pointing at the memory that led the prompt. Agents carry
  at most 64 of a kind (256 of building materials) and leave the rest.
- Every timeline event says why: events logged without explicit causes
  take the agent's current decision and its top factors
  (`population/EventExplainer`).
- Practices (`RecipeBook.REPLANTING`): ways of working, known or only
  heard of, saved with the mind. Nobody starts knowing one. Replanting is
  learned by seeing a noticed sapling (anyone's, a player's included)
  later standing as a tree (`action/Forestry`); curious agents and those
  who've heard of it sometimes plant a sapling to see; knowers replant
  where they fell trees and pass it on in conversation as hearsay (`Told`).
  Felling takes the whole tree (`PhysicalActions.treeLogs`).
- Monsters hunt agents (`entity/MonsterHunting`, `monstersHuntAgents`):
  zombies, skeletons, spiders and creepers get an agent target goal on
  load, used only while they have no other target. A monster's blow is
  felt by the mind (`NeedsDrivenGoal.onHurtByMonster`): safety drops, the
  agent re-decides at once with "being attacked" weighing on fleeing, and
  the first blow of a fight is a memory and an `ATTACKED` event. `FIGHT`
  is offered with a monster (not a creeper) within 12 blocks in sight, and
  scored on nerve, a sword, being attacked, someone else attacked, and
  health; fleeing weighs more for the cautious and the badly hurt. Resting
  in the open restores little; homelessness without wood pulls toward
  gathering. The prompt says dawn ends the night's danger.
- Helping and following through: a starving agent in sight is perceived
  ("looks half-starved") by those with food to spare, and pulls the kind
  toward them (`someone looks starving`); a starving agent whose searches
  keep failing may socialize, undamped, hoping someone shares; food is
  offered whichever side starts the talk. A dialogue plan both take on to
  build becomes a `CoBuilding` project. Failed tree searches are remembered
  as such, dark or not, with no rule against night work. Seeing a monster
  costs less safety (0.004 a decision).
- Writing (`RecipeBook.WRITING`, `action/Writing`, `action/SignWords`,
  intent `WRITE_SIGN`): nobody starts knowing that words left on a sign
  stay for whoever passes. It's learned by reading someone else's sign (a
  player's included) or by trying it; the quite curious and those who've
  heard of it try. An agent writes only what it knows that people passing
  would want: where the trees are, near its home, when there are none in
  sight (wood is scarce), or a warning where a monster came for it at
  night. Not when a sign close by says as much, not near buildings (a sign
  where a wall should go would stop a builder), and only where a sign can
  stand. A sign costs two planks or a log. Readers get the words and
  nothing else, as a memory with a fourth provenance, `Read(documentId,
  authorId, authorMemoryId)`, traced through `world/Library` (observer-side
  bookkeeping of every sign; agents never consult it) to the memory it was
  written from. A reader understands a sign from its words (`SignWords`),
  so "forest 60 north" on a player's sign sends agents looking just as an
  agent's own does; a warning read at night costs a little safety. Knowers
  pass writing on in conversation like replanting; what was read can be
  retold, keeping the chain. `WROTE` and `READ` events; read memories show
  as "read on a sign by ..." on the observer. Sign text is from templates;
  Claude-written signs, books, and literacy as its own practice are future
  work.
- Sight and range: perception reaches 24 blocks for animals and monsters
  and 32 for people, needing line of sight beyond 8; trees are spotted to
  48 blocks (a volume scan to 12, then column tops on loaded chunks,
  leaves judged at the crown, the foot targeted). Long walks (explore 80,
  wood 96, food 48-160) go in legs of 28 over loaded ground
  (`NeedsDrivenGoal.nextLeg`); `dryGroundAt` never loads a chunk.
- Need rates: social decays 0.000012 and belonging 0.000005 a tick
  (a crisis after about three days alone); company adds 0.00004 social
  and 0.000008 belonging a tick, daylight without a monster in sight adds
  0.00006 safety, company at night 0.00001 (`feelSurroundings`). A wander
  with nowhere reachable sets `boxedIn`: climb or scramble out at once, a
  failed food search if foraging.
- Places (`mind/Places`): fields, woods, saplings, animals, water, stuck
  spots and homes seen, as plain coordinates in the mind, merged when
  close, capped and faded per kind, saved in `AgentMindNbt`. Replaces the
  body's unsaved `myFields`, `knownWoods` and sapling watch list, which a
  restart wiped (farmers lost their fields and starved).
- Digest (`digest/DigestBook`, `DigestLog`): per agent per game day,
  from position samples and events (it keeps its own place in the log);
  raises alerts. Observer-side only. Served at `/api/digest`,
  `/api/alerts` and as guest attributes.
- Chronicle stories (`story` package): `StoryGrouper` (pure, tested)
  folds the event log into stories (events sharing people, naming each
  other's people, or linked by an `EVENT` cause; closed after a quiet
  quarter-day, a game day's span or 24 events), counts routine work per
  agent per day, and leaves bookkeeping out. `StoryWriter` asks the LLM to
  write each settled story up from a `StoryBrief` holding only its events
  (`storyIntervalTicks` apart, last two game days only, none while the
  simulation is off); `StoryLog` saves stories and write-ups with the
  world. Observer-side only: agents never read stories. Served at
  `/api/stories`, published as the `stories` guest attribute, and shown
  as the Chronicle tab, each with its record under "What happened".
- Written conversations: a conversation that makes the timeline is written
  out by the LLM from a `DialogueBrief` built where the two meet (each
  side's needs, home, recent first-hand memories and feelings toward the
  other), rate-limited across the population (`dialogueIntervalTicks`).
  The lines are stored on the event as a transcript and each agent
  remembers the gist as an `Inferred` memory below news importance (the
  model wrote it, the agent didn't see it), told to use only what that agent
  knew or heard said aloud. Agreements returned with it are checked against the
  world and carried out: gifts and swaps (only what the giver holds),
  plans (become goals via `addGoal`), building together (`CoBuilding.agree`). Repeat small talk by the same pair is kept off the
  timeline.
- Agents that can't get back home three trips in a row give it up and
  settle elsewhere; with 64+ blocks and no clearing, they build among trees.
- Food beyond hunting (`action/FoodActions`, intent `FARM`): picking ripe
  berries and crops (replanting what it harvests), collecting seeds from
  grass, digging farmland near water and planting it, going back to tend
  its own fields (each visit moves nearby crops one stage on), feeding
  pairs of livestock so they breed, and baking bread from three wheat.
  Farming appeals as food searches keep failing (a mind-side counter fed
  by the embodiment, no telepathy). Field chunks stay loaded while agents
  run so crops grow. An empty stomach slowly costs health and can kill
  (`starvationDamageIntervalTicks`); a fed agent heals.
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
- A read-only observer web page (`observer` package): a snapshot of the
  overworld's population and recent events is built on the server thread
  once a second, and a JDK `HttpServer` serves only that immutable copy, so
  observing can't change or race the simulation. It sits outside the
  epistemic boundary like `/civ inspect` does: it reads minds, never writes
  them. Token-protected; see the README.
- An automated JUnit suite covering memory/provenance behavior, decision
  scoring, population lifecycle, and — most importantly — the epistemic
  boundary itself, so principle #2 can't silently regress.
- Bedrock Edition support through Geyser. Geyser can't decode a modded
  Java entity type (it logged `Index 158 out of bounds for length 158` for
  every agent spawn), and its extension API can't add one, so
  `AgentEntity` implements Polymer's `PolymerEntity` and is sent to
  clients without the mod as a villager. Polymer is bundled in the mod
  jar. See the main README's "Playing from Bedrock Edition" section. `AICivilizationMod` also marks the entity-type
  registry `RegistryAttribute.OPTIONAL` — without it, Fabric API's
  registry-sync handshake kicks any connection that can't prove it has
  this mod installed, which includes Geyser's internal Bedrock-to-Java
  bridge (it can never install a server mod); this surfaced as a real
  "This server requires Fabric Loader and Fabric API" disconnect the
  first time a Bedrock client actually tried to connect.

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
- **Physical action fidelity (partly done).** Agents now really chop natural
  trees, hunt livestock, collect the drops, eat when hungry (inventory lives
  in `AgentMind` as plain item ids; `action/` holds the world-touching code)
  and build their homes from logs/planks. A building is a `mind/Design`
  (layered text drawing, validated by `DesignValidator`): each agent gets
  its own from one Claude call (`DesignBrief`), or procedurally from
  `DesignGenerator`, with the 3x3 hut as the innate fallback. The first
  building an agent finishes becomes its `Home`: it returns there at night
  (`GO_HOME`), sleeps (`REST` at home), repairs damage, and farms nearby.
  Designs spread by imitation (`behavior/Imitation`: seeing a standing home,
  or hearing one described, each with drift) and agents can agree to build a
  home together (`behavior/CoBuilding`, `mind/Project`: site shared by
  telling, never telepathy). Cooking at a furnace by the door
  (`action/Cooking`).
  Movement is kept honest about terrain: no pathing into water, a float
  reflex, shore-seeking, and a scramble out of peaks and pits after half a
  minute of getting nowhere (with back-off), and steps cut out of any narrow
  hole it finds itself in (checked every few seconds, destination or not); if that keeps failing they swim
  across water or cut steps up a hillside, and avoid places they got stuck. Guard rails:
  only natural trees (non-persistent leaves nearby), only whitelisted
  non-baby unnamed livestock, only freshly dropped items, no building near
  trees. Since then: crafting tools from the pack (`action/Crafting`), mining
  exposed stone and digging out of caves (`behavior/CaveEscape`, natural ground
  only), and trade and help between agents (`behavior/TradeBehavior`, valued
  per agent by `mind/ItemValue`). Still missing: storage, crafting tables and
  furnaces, longer-lived bargains.
- **Custom visuals.** Agents render using the vanilla zombie model/texture
  (`AgentRenderer`) purely so they're visible without needing new art
  assets. A distinct look is cosmetic, not a milestone concern.

**Minecraft 1.21.1 → 26.2 migration.** Minecraft switched to year-based
versioning in 2026 and, starting at 26.1, ships unobfuscated with Mojang's
own official names — Yarn mappings don't exist for 26.x+ at all. Porting
used Loom's own two-step recommended path: first an automated
`migrateMappings` rename pass (Yarn → official names) while still on
1.21.1, verified green, then the version bump to 26.2 itself. The actual
Fabric Loom Gradle *plugin* also changed id (`fabric-loom` →
`net.fabricmc.fabric-loom`) and dropped the mod-remapping step entirely
(`modImplementation` → plain `implementation`, `remapJar` → plain `jar`).
Beyond renames, a few real API redesigns needed code changes, not just
identifier substitution: `CompoundTag`'s getters now return `Optional<T>`
(or a `getXxxOr(key, default)` convenience variant); NBT UUID storage
dropped its `putUuid`/`getUuid` helpers in favor of
`tag.store(key, UUIDUtil.CODEC, value)` / `tag.read(key, UUIDUtil.CODEC)`;
`PersistentState` was replaced by a much smaller `SavedData` base class
plus an external `SavedDataType<T>` record carrying a `Codec<T>` (here,
`CompoundTag.CODEC.xmap(...)` wrapping the existing imperative NBT
read/write logic, rather than a full field-by-field `RecordCodecBuilder`
rewrite); `ResourceLocation` was renamed `Identifier`; Fabric's entity
type builder and command registration APIs moved packages/signatures;
and entity rendering now goes through a per-frame "render state" object
(`LivingEntityRenderer<T, S extends LivingEntityRenderState, M>`) rather
than querying the entity directly inside render methods.

**Build verification performed:** `./gradlew build` (compiles `main`,
`client`, and `test` source sets against the real Minecraft 26.2 jar,
which ships unobfuscated with Mojang's own official names — no Yarn
mappings or remap step, since 26.x+ doesn't need either) and
`./gradlew test` both pass. This is a genuine compiler-verified check
against the real Mojang-mapped API, not just code that "looks right." The
mod was ported from Minecraft 1.21.1 to 26.2 so it can run with a current
Geyser build (Geyser 2.11+ requires 26.2) — see the migration note below.
What was **not** done in this environment:
launching a client or dedicated server and actually playing with spawned
agents, and connecting an actual Bedrock client through Geyser to confirm
agents render — both require a graphical/interactive session
and a real Bedrock client this environment doesn't have. Everything above
should be read as "compiles and is internally consistent," not as "has
been played." (Since then, a Bedrock player on the live 26.2 server has
joined through Geyser and Floodgate and seen spawned agents as villagers.)
