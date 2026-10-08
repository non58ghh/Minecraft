---
name: add-agent-behavior
description: Checklist for adding or changing what agents can do — a new IntentType, physical action, perception, conversation purpose, memory/belief/goal source, or event type — without breaking the project's four design principles. Use whenever a change touches mind/, behavior/, perception/, action/, reasoning/ or events/.
---

# Add or change an agent behavior

Read `DESIGN.md` §2 and §9 first. The four principles are load-bearing and
partly enforced by tests:

1. **Mind ≠ entity.** `mind/` and `population/Population` import nothing
   from `net.minecraft.*`. Only `PopulationRegistry` / `AgentMindNbt` touch NBT.
2. **No telepathy.** No method in `com.aicivilization.mind` may accept a
   world/server type, `EventLog`, `PopulationRegistry`, or another
   `AgentMind` (`AgentMindEpistemicBoundaryTest` enforces this). Pass
   primitives (`String`, `double`, `UUID`) extracted by perception. Every
   memory carries a `Provenance` (`Perceived` only from `PerceptionSystem`,
   `Told` only via `ConversationBehavior`, `Inferred` from reasoning).
3. **No scripted roles.** No occupations, quests, `setRole`, or
   `if trait > x then always do y` tables. Every agent can choose every
   intent; scoring blends needs, personality and randomness.
4. **Observability.** Decisions produce a `DecisionTrace`; significant
   happenings become typed `SimEvent`s with `Cause`s, not ad-hoc strings.

## Touch points for a new intent

Grep `GATHER_MATERIALS` to see a complete recent example. Usually:

- `mind/IntentType.java` — add the constant with a one-line javadoc.
- `perception/PerceptionSystem.java` or `behavior/NeedsDrivenGoal.java` —
  when the intent is physically *possible* (added to the available set).
- `mind/AgentMind.decide` — how it is *scored* (factors appear in the trace).
- `behavior/NeedsDrivenGoal.java` — the `switch` cases that drive it each
  tick, and the human-readable label near the bottom.
- `action/` — any world-touching code (block breaking, items). Keep guard
  rails like the existing ones (natural trees only, no babies/named mobs).
- `reasoning/HeuristicReasoningProvider.java` — `GOAL_TEMPLATES` and the
  softmax score; `AnthropicReasoningProvider` parses intent names, so check
  its prompt lists the new one.
- `observer/` (`ObserverJson`, `app.js`) if the observer should show it.
- `AgentMindNbt` if new mind state must survive a restart.

## Before committing

- Add/extend tests under `src/test/java` mirroring the package.
- Run `./gradlew test`; the epistemic-boundary test must pass.
- Update `DESIGN.md` §10 (delivered vs. deferred) and `README.md` if
  user-visible.
- Then follow the `ship-jar` skill.
