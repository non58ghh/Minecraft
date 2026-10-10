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

### The first settlement

A world that has never had an agent gets its founders automatically: on
server start, `foundingAgents` agents (default 10) are spawned in a spread
around the world spawn. This only happens when the population is completely
empty, living or dead, so a settlement that dies out is never quietly
refilled. Values above `maxAgents` are capped to it. Set it to 0 to found
settlements yourself with `/civ spawn`:

```json
"foundingAgents": 0
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
  "reasoningIntervalTicks": 6000,
  "reasoningCrisisCooldownTicks": 1200,
  "reasoningNoveltyThreshold": 0.1,
  "maxReasoningCallsPerAgentPerDay": 200,
  "maxApiCallsPerHour": 40,
  "dialogueIntervalTicks": 2400,
  "storyIntervalTicks": 2400
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

`maxApiCallsPerHour` (default 40, 0 = no ceiling) caps every API call
together, per real-time hour: reasoning, home designs, written conversations
and chronicle write-ups. Calls refill steadily over the hour, and the
allowance starts half full after a restart. Agents' own thinking comes first;
written conversations and write-ups are made only while at least half the
hour's calls are left, so they never crowd out an agent. With none left, an
agent carries on without stopping to think (a turn of day or night passes
unremarked), a conversation goes untranscribed, and a story waits.

`anthropicMaxTokens` below 300 is raised to 300: a full answer runs to
100-250 tokens, and a reply cut short is lost. A pass that comes to nothing
(an API error, a reply cut off or unreadable) shows on the observer as
"Thinking failed" with the reason, rather than as a thought about nothing.

When an agent stops to think, Claude is given that agent's own situation
in plain words: the day and time (with a warning when night is near),
its character, how it's doing (hungry, but carrying bread), where it is
and who's in sight, the people it knows and how it feels about them,
what's been happening lately (chores folded into one line a day), what
it's working on and how far along, recent setbacks, and what each
activity really does. It picks the activity first and then writes its
goal as what that activity will do, since the game acts on the activity,
not on names or places in the goal.

What Claude writes goes back into the world carefully. A goal shapes
which activity the agent leans toward, not literally what the words say,
so when an agent has worked at a goal a few times it remembers "I spent
time on: ..." rather than claiming success, and that isn't passed on as
news. What each agent takes away from a written conversation is kept as
its own reading of the talk (not as something it saw) and isn't spread
either. A new thing to make doesn't cancel one already in progress; a plan
pushed out by newer goals is remembered as set aside. A belief is traced
to the memory that led the prompt it came from.

Conversations that make the timeline are written out by the same model: a
few lines of real dialogue, grounded in what the two agents know (their
needs, home, recent experiences and how they feel about each other), and
each remembers the gist. What they agree is carried out: a gift or swap
changes hands on the spot if the giver really has it (a broken promise is
remembered, and costs trust), a plan becomes a goal that weighs on what
each does next, and agreeing to build a home together starts a shared
project. Each shows on the timeline, linked back to the conversation.
That's one call per written conversation, at most
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

## Monsters

Vanilla Minecraft only spawns hostile mobs near players, so with nobody
online the agents' nights were perfectly safe. Monsters now come to agents
the way they come to players: every second, for each agent with no player
within 128 blocks, one spawn attempt 16 to 28 blocks away, by the game's
own rules (dark enough, a kind the biome spawns, a spot that kind may stand
on), while fewer than 8 monsters are within 64 blocks. Monsters spawned
this way despawn as vanilla's do near players: at once more than 128 blocks
from every agent and player, now and then beyond 32. The chunks all round
each agent (3x3) are kept running, so a monster closing in actually moves.
Part of `monstersHuntAgents`.

Zombies (husks and drowned too), skeletons, spiders and creepers hunt
agents as they hunt players: on sight, and spiders only in the dark. A
monster already after a player keeps after the player.

Agents fight back, or run: with a monster close (creepers excepted, which
they keep away from), standing to fight is one of their choices. Whether
they take it is their own nerve (risk), whether they carry a sword (they
make wooden and stone ones), how hurt they are, and whether the monster is
going for someone else (the sociable step in). A cautious agent runs; a bold
or armed one turns on it; badly hurt, anyone runs. A kill is remembered
("I killed a zombie that was going for Iris.") and logged, with the person
saved sharing the event. A blow takes a quarter off an agent's sense of
safety and makes it decide again at once; the first blow of a fight is
remembered ("A zombie attacked me.") and logged as an
`ATTACKED` event, and a death at a monster's hands reads "was killed by a
zombie". A monster in sight wears safety down a little at each decision
(gently: the fear shouldn't outrun the danger). A
well-fed agent heals a point of health every 10 seconds.

Resting in the open no longer makes an agent feel at home: by day it calms
it a little, at night not at all; only resting at home restores safety and
belonging properly. Without a home or the wood for one, gathering wood
(and building) gets a pull of its own, stronger the more rootless the agent
feels, and a goal of building lends weight to fetching the wood for it.

```json
  "monstersHuntAgents": true
```

`monstersHuntAgents` (default true) set to false leaves agents off the
monsters' lists, as in vanilla. Takes effect after a restart.

## Homes and designs

Each agent imagines its own home the first time it sets about gathering
wood or building: one Claude call per agent, ever, with its personality and
recent memories, answered as a layered drawing (`#` solid, `.` inside,
`D` doorway). Homes are meant to be lived in: up to 13 by 13 blocks, 8
layers high and 400 blocks, and Claude is encouraged to think in rooms,
halls, stepped roofs, porches and towers. The drawing is checked (size,
walls all round, a roof, a way in from the door to every room) and anything
that doesn't hold up, or any agent without Claude configured, gets a design
drawn procedurally from its personality instead (5 to 11 across, sometimes
two rooms): ambitious agents build bigger, curious ones taller. A big home
takes many trips for wood; building goes on a batch at a time. Agents
notice standing trees up to 48 blocks away (close ones all round, further
ones by their crowns over the land in view) and remember where they've
seen them; one without a home and short of wood will go for it even with
no tree in sight, heading back to trees it remembers or further afield to
look, and forgets a stand once it finds it cleared. A tree comes down
whole: every log joined to the one it cuts, branches included, goes into
its pack (what it can't carry falls where it stood). The leaves are left
to wither as they would, dropping saplings.

Nobody starts out knowing that a sapling grows into a tree. An agent
learns it by seeing it: saplings it notices in the ground (planted by
anyone, a player included) are kept an eye on, and if it passes one later
and finds a tree standing there, it knows, and that's a milestone in the
chronicle. Curious agents sometimes pick saplings up and set one in the
ground just to see what becomes of it. Once it knows, an agent keeps the
saplings it comes across and plants one where each tree it fells stood,
and it can tell others when they talk; to them it's hearsay, which makes
them more willing to try planting, until they see one grow themselves.
Saplings only grow where the world is loaded (near agents or players), so
this can take a while. You can show them: plant a sapling near an agent. Until a design
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
help. A well-stocked agent that meets a hungry one offers food unasked,
whichever of them started the conversation. Someone in sight who is close to
starving looks it ("Elias looks half-starved."): an agent with food to spare
is drawn to go over to them, the more so the more sociable it is. A starving
agent whose own searches for food keep failing may instead go to people,
hoping someone will share. Each values things by its own situation (food is worth most to the
hungry, wood to the homeless, a missing tool a lot), and a trade only
happens if both come out ahead, with a little slack for someone trusted.
An agent in need asks for food; whether it gets some depends on whether the
other can spare it and how sociable, fond or trusting they are. News is
first-hand only and never told to the same listener twice, and the same pair
doesn't strike up a conversation more than once every half minute.

Two people who agree in conversation to build a home together get a shared
project (a site, blocks from both, a shared home), whether the conversation
called it building together or a plan they both took on. Plans already
agreed stand: agents are told they needn't meet again to confirm them.

An agent that walks out looking for trees and arrives with none in sight
remembers it plainly ("I looked for trees in the dark and found none."), at
most every two minutes. Nothing stops agents gathering at night; whether
they learn not to is up to them.

## Signs and writing

Nobody starts out knowing that words left on a sign stay there for whoever
passes. An agent finds out by reading a sign someone else put up, or by
trying it itself, which only the quite curious do unprompted (and anyone
who's heard of it from someone who knows). After that it puts a sign up
when it knows something people passing would want to know:

- **where the trees are**, near its home, when there are none in sight
  (`Trees / 48 blocks / north-east / - Linnea d33`);
- **a warning** where a monster came for it at night
  (`Beware / zombies / at night / - Bram d12`).

It won't write where a sign close by already says as much, near anything
being built, or without two planks (or a log) to make the sign from.

Agents read every sign within six blocks, once each. A reader remembers
exactly what the sign said, traced back to the memory its writer wrote it
from. That trace survives the writer's death. Readers act on what they
read: a sign pointing to trees gives them somewhere to look, and a warning
read at night unsettles them. They can pass on what they read in
conversation, too.

**You can write to them.** Agents read players' signs the same way. Put up
a sign saying `forest 60 north` (a word for trees, a direction, and
optionally a distance in blocks) and agents who read it will go and look.
Any sign at all teaches the agent who reads it that writing exists. The
observer shows writing under its own filter, and read memories as "read on
a sign by ...".

## Goals that name a thing, and plans

When an agent reflects, it may set a goal to have something: "make an iron
pickaxe" with a target of one iron pickaxe. The target is checked against
the game's real items (an invented one is dropped and remembered as such);
food and crops are left to farming and foraging. The agent then works it
out backwards from its own recipe book, never from the game's full recipe
list: the iron pickaxe needs ingots, ingots need raw iron smelted with coal
in a furnace, raw iron needs a stone pickaxe, and so on down to logs. The
plan shows on the timeline, step by step.

Plans pause and resume: eating, sleeping or fleeing come first, and when it
picks the goal up again it plans afresh from what it carries, so nothing is
lost. Crafting happens at a real crafting table (set up from its pack if
none is near); smelting loads a real furnace, which takes its time, and the
agent comes back for what's done. A furnace with someone's things cooking
in it is theirs until they collect. When the ore (or, under soil, the
stone) it needs isn't in sight, it cuts a staircase down looking for it,
in daylight and fed, and comes back up when it has enough, gets hungry,
night falls or it has gone deep enough. A step that keeps failing ends in
giving the goal up, said on the timeline; a recipe it doesn't know is
named as what it would need to learn.

Each agent's recipe book records how it learned each thing: known from the
start (planks, sticks, a table, wooden/stone/iron tool shapes, a furnace,
bread, cooking, where wood, stone, coal and iron come from), made, seen or
told (heard-of recipes are only hints until made). Only the more curious
agents start out knowing how to smelt iron. Goals with a target finish when
the item is in hand; other goals finish after the agent has acted on them a
few times.

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
climbs free to the nearest open dry ground within six blocks (down a cliff,
or up to three blocks out of a hole): "got stuck and climbed free". One down a narrow hole cuts steps
out straight away, even if it had nowhere in mind; if that doesn't help it tries less
and less often. An agent that keeps getting stuck may be cut off: it will swim
across water to land on the far side, or dig steps up the slope to get out
of a strip at the foot of a cliff ("couldn't find a way out and dug steps
up the slope"). Wherever it got stuck is avoided for a
while, and when choosing where to wander it picks somewhere it can actually
get to rather than a spot beyond a cliff.

Daybreak and nightfall each prompt an agent to stop and think again, as a
crisis or something new does, and each fresh thought supersedes the goal
the last one set (a plan to make something, or one agreed with someone,
stands), so "rest until dawn" doesn't outlive the dawn. A lonely agent with
nobody in sight can always go looking: to someone it remembers seeing
lately, else to a home it knows of, else out searching up to 80 blocks.

When they talk, an agent may tell the other where it has seen trees or
animals, or where it got stuck ("Mabry told me there are trees about 60
blocks north-east of where we talked"). Only places it saw itself are told,
picked at random. To the listener it's hearsay, somewhere to try: a hungry
agent with nothing in sight picks at random between the places it knows of
animals and searching afresh. If it goes and finds nothing there, it
remembers who told it and trusts them a little less.

Agents also keep track of their own failures: searching for trees or food
and finding none, getting stuck, being attacked, being sent somewhere empty.
The third of the same kind within three days is noticed, plainly ("I've
been attacked by monsters three times in the last 2 days, every time at
night, and always around the same place"). It becomes a memory of their own
working out that comes up when they think things over or talk, and it shows
in the chronicle as a lesson. What to do about it is theirs to decide.

Needs wear down slowly on their own: a day alone takes about a third off an
agent's sense of company and an eighth off belonging, so loneliness becomes
a crisis after about three days without anyone. Time in someone's company
eases both. Fear fades in daylight with no monster in sight (a bad scare
over part of a day), and company takes the edge off the night; only resting
at home restores safety and belonging quickly. An agent that looks for
somewhere to walk and finds nowhere it can get to (down a hole, in a cave)
treats itself as boxed in and digs or climbs out at once, and if it was
looking for food, counts that as a failed search.

Agents remember places as part of their mind, saved with it: fields they
planted, stands of trees, saplings they're watching, where they saw
animals, water, spots where they got stuck, and whose home stands where.
Places fade if not seen again (animals after a day, a field after ten).

Agents notice animals and monsters within 24 blocks and people within 32,
but beyond 8 blocks only what's in plain view, not behind a hill or a wall.
They range further too: exploring heads up to 80 blocks out, a search for
trees up to 96 and for food from 48 out to 160 as searches keep failing.
A long walk goes a leg of about 28 blocks at a time, each leg planned over
ground that's loaded, bending around water and cliffs; the world isn't
loaded just to choose where to go. Every agent gets a name no other agent has had.

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

Each agent's last seven game days are kept as a digest (`/api/digest`,
saved with the world): how far it went, the longest it stood in one spot,
what it ate and where its food came from (harvested, hunted, given,
traded), who it met, its lowest health, its needs at day's end, and how it
died. Alerts (`/api/alerts`) stand while someone is starving, has stood in
one spot away from home for a day, or hasn't left home in three days, and
for three days after a death; each says when it was first raised, so a
scheduled check can notify about new ones only.

The mod serves a read-only web page, styled as a small-town newspaper, for
watching the simulation from a phone or browser: **Today** leads with the
most interesting recent happening and lists who's doing what; **People**
is a directory of everyone living there, each with their own page (a
pixel portrait, condition in plain words, what they're carrying, their
home, a floor plan, who they know and how they feel about them, their
current plan if they have one, their goals and beliefs, and their
memories with where each came from — plus, further down, the raw
decision scores for anyone who wants them); and **Chronicle** tells what's
going on as short stories.

The masthead is a sky that follows the game clock (sun by day, moon and
stars by night). **Today** opens with a map of everyone whose body is
loaded, north up, drawn from the world itself the way a Minecraft map item
draws it: each block's map colour, slopes shaded, water darker with depth,
framed like a map (`/api/terrain`, redrawn every 30 seconds from loaded
chunks only; unloaded land stays blank, as unexplored parts of a map do).
On it: people as their portraits, homes as little houses, and monsters
within 24 blocks of anyone as pixel heads (zombie, skeleton, creeper,
spider and their kin), with a dashed line to whoever a monster is after.
At night the map darkens. Everyone shows their health as hearts, two points to a heart as in
the game. New events slide in as they arrive (the page refreshes every few
seconds); animations are off for anyone who asks their device for reduced
motion.

Each story is one thing that happened between people: what someone was
thinking, what they did about it and how it turned out, as a headline, a
paragraph and "where it stands", newest first under each day. The server
groups the event log into stories as it goes (events that share people,
name each other's people, or were caused by one another; a story ends
after a quiet quarter-day or once it spans a game day), and the model
writes each one up from its events and only from them. "What happened"
under every story lists those events, so a write-up can always be
checked. A single thought or piece of news is a one-line "Also", and
routine work (harvesting, eating, chopping, placing blocks) is only
counted, per person per day. **Every event** is the full timeline as
before, filterable by kind or by person, with causes linking back to the
events and memories behind them.

Each write-up is one API call. `storyIntervalTicks` (default 2400, two
minutes of play) is the shortest gap between them across the server; 0
turns write-ups off, and stories then show their events instead. Only
stories from the last two game days are written up, so a long backlog
(the first time the mod starts on an old world) doesn't turn into hours of
calls. Nothing is written while the simulation is switched off.

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
to tell any client without the mod that each agent is a player. Bedrock
and vanilla Java players see agents as players wearing one of the default
skins (picked from the agent's id, so it never changes) with their name
above their head. Agents don't appear in the tab list. Names are trimmed to
what a player name allows: 16 letters, digits or underscores. The
server-side entity and its mind are unchanged. Install
[Floodgate](https://modrinth.com/mod/floodgate) alongside Geyser (with
Geyser's `auth-type: floodgate`) to let Bedrock players join without a
Java account.

## Status

Compiled and internally consistent against the real, official-mappings
Minecraft 26.2 API, with a JUnit suite covering memory/provenance
behavior, decision scoring, and the epistemic boundary itself. Not yet
manually played through in a graphical client/server session — see
DESIGN.md §10 for exactly what's implemented versus deferred.
