package com.aicivilization.reasoning;

import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Design;
import com.aicivilization.mind.DesignGenerator;
import com.aicivilization.mind.KnownDesign;
import com.aicivilization.mind.Goal;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Provenance;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Decides <em>when</em> to invoke the "deep system" for a given agent
 * (throttled, novelty-gated — a real LLM call is not cheap) and applies the
 * result back once it resolves. The pacing rules live in
 * {@link ReasoningGate}: routine reflection at most every
 * {@code intervalTicks} and only when something changed, plus one pass on
 * entering a need crisis.
 */
public final class ReasoningScheduler {

	private final ReasoningProvider provider;
	private final ReasoningGate gate;
	/** Agents whose design request is in flight, so each is asked for once. */
	private final Set<UUID> designing = new HashSet<>();

	public ReasoningScheduler(ReasoningProvider provider, long intervalTicks, long crisisCooldownTicks,
			double noveltyThreshold, int maxCallsPerAgentPerDay) {
		this.provider = provider;
		this.gate = new ReasoningGate(intervalTicks, crisisCooldownTicks, noveltyThreshold, maxCallsPerAgentPerDay);
	}

	public void maybeInvoke(AgentMind mind, long tick, EventLog log, Executor mainThreadExecutor) {
		UUID id = mind.identity().id();
		Needs needs = mind.needs();
		String crisisNeed = needs.hasCrisis() ? needs.lowestName() : null;
		double[] needValues = {needs.food(), needs.safety(), needs.social(), needs.belonging()};
		ReasoningGate.Trigger trigger = gate.check(id, tick, crisisNeed, mind.memories().peekNextId(), needValues);
		if (trigger == null) {
			return;
		}

		AgentContext context = buildContext(mind, tick);
		String reason = trigger == ReasoningGate.Trigger.CRISIS ? "a " + crisisNeed + " crisis" : "routine reflection";
		log.append(tick, EventType.REASONING_INVOKED, List.of(id),
				mind.identity().name() + " stopped to think, prompted by " + reason + ".", List.of());

		provider.reason(context)
				.thenAccept(result -> mainThreadExecutor.execute(() -> apply(mind, result, tick, log)));
	}

	/**
	 * An agent without a home and without a design of its own imagines one:
	 * one request, ever, per agent, made early so the design is ready by the
	 * time it has gathered enough to build.
	 */
	public void maybeDesign(AgentMind mind, long tick, EventLog log, Executor mainThreadExecutor) {
		UUID id = mind.identity().id();
		if (mind.hasOwnDesign() || mind.home().isPresent() || designing.contains(id)) {
			return;
		}
		designing.add(id);
		mind.setImaginingDesign(true);
		DesignBrief brief = new DesignBrief(mind.identity().name(), id.getMostSignificantBits() ^ tick,
				mind.personality().curiosity(), mind.personality().risk(),
				mind.personality().sociability(), mind.personality().ambition(),
				mind.memories().retrieve(tick, 5).stream().map(MemoryEntry::description).toList());
		log.append(tick, EventType.REASONING_INVOKED, List.of(id),
				mind.identity().name() + " stopped to imagine the home they want to build.", List.of());
		provider.design(brief)
				.exceptionally(ex -> Optional.empty())
				.thenApply(design -> design.or(() -> Optional.of(DesignGenerator.generate(brief.agentName(),
						mind.personality(), brief.seed()))))
				.thenAccept(design -> mainThreadExecutor.execute(() -> {
					designing.remove(id);
					mind.setImaginingDesign(false);
					design.ifPresent(d -> applyDesign(mind, d, tick, log));
				}));
	}

	private static void applyDesign(AgentMind mind, Design design, long tick, EventLog log) {
		mind.learnDesign(new KnownDesign(design, "designed", "", null, tick));
		String size = design.width() + " by " + design.depth() + ", " + design.height() + " blocks high";
		mind.inferMemory(tick, "I imagined the home I want to build: " + ("aeiou".indexOf(design.kind().charAt(0)) >= 0 ? "an " : "a ") + design.kind() + ", " + size + ".", 0.6, -1);
		log.append(tick, EventType.REASONING_RESULT, List.of(mind.identity().id()),
				mind.identity().name() + " designed a home of their own: " + design.name() + " (" + size + ").", List.of());
	}

	private void apply(AgentMind mind, ReasoningResult result, long tick, EventLog log) {
		result.goalDescription().map(ReasoningScheduler::sentenceCase).ifPresent(description -> {
			boolean alreadyPursuing = mind.goals().stream()
					.anyMatch(g -> g.active() && g.description().equals(description));
			if (!alreadyPursuing) {
				String target = result.targetItem().map(ReasoningScheduler::itemId).orElse(null);
				if (target != null && !com.aicivilization.world.RecipeCatalog.isItem(target)) {
					// The model named something that doesn't exist; the agent keeps the wish but not the nonsense.
					mind.perceive(tick, "I thought of making a " + result.targetItem().get().replace('_', ' ')
							+ ", but there's no such thing.", 0.3, java.util.Set.of());
					target = null;
				}
				mind.addGoal(tick, description, result.goalPriority(), result.relatedIntent().orElse(null), target,
						Math.min(64, Math.max(1, result.targetCount())));
				if (target != null && !mind.recipeBook().knows(target)) {
					mind.perceive(tick, "I want " + result.targetItem().get().replace('_', ' ')
							+ ", but I don't know how to make it yet.", 0.5, java.util.Set.of());
				}
			}
		});

		result.beliefStatement().ifPresent(statement -> {
			MemoryEntry sourceMemory = mind.memories().retrieve(tick, 1).stream().findFirst().orElse(null);
			long sourceId = sourceMemory != null ? sourceMemory.id() : -1;
			mind.formBelief(tick, statement, result.beliefConfidence(), new Provenance.Inferred(sourceId));
		});

		log.append(tick, EventType.REASONING_RESULT, List.of(mind.identity().id()),
				mind.identity().name() + " concluded: " + summarize(result), List.of());
	}

	/** "Find people" becomes "find people", so it reads as "wants to find people"; "Iris" or "AI" stay as they are. */
	static String sentenceCase(String text) {
		String t = text.strip();
		if (t.length() < 2 || !Character.isUpperCase(t.charAt(0)) || Character.isUpperCase(t.charAt(1))) {
			return t;
		}
		String firstWord = t.split("\\s+", 2)[0];
		// Leave names alone: a capitalised first word that isn't a common opening verb is likely a name.
		if (!COMMON_OPENERS.contains(firstWord.toLowerCase(java.util.Locale.ROOT))) {
			return t;
		}
		return Character.toLowerCase(t.charAt(0)) + t.substring(1);
	}

	private static final Set<String> COMMON_OPENERS = Set.of("find", "build", "harvest", "search", "locate", "trade",
			"reach", "establish", "explore", "gather", "get", "make", "plant", "grow", "learn", "seek", "share", "ask",
			"help", "collect", "stay", "keep", "go", "return", "head", "secure", "protect", "meet", "talk", "befriend",
			"craft", "hunt", "eat", "rest", "settle", "survive", "store", "cook", "bake", "start", "create", "join", "invite");

	private static String summarize(ReasoningResult result) {
		if (result.goalDescription().isPresent()) {
			return "wants to " + sentenceCase(result.goalDescription().get());
		}
		if (result.beliefStatement().isPresent()) {
			return result.beliefStatement().get();
		}
		return "nothing in particular this time";
	}

	/** "iron_pickaxe", "Iron Pickaxe" or "minecraft:iron_pickaxe" to an item id. */
	static String itemId(String name) {
		String id = name.strip().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
		return id.contains(":") ? id : "minecraft:" + id;
	}

	private static AgentContext buildContext(AgentMind mind, long tick) {
		mind.expireGoals(tick);
		List<String> memories = mind.memories().retrieve(tick, 8).stream()
				.map(MemoryEntry::description)
				.toList();
		List<String> goalDescriptions = mind.goals().stream()
				.filter(Goal::active)
				.map(Goal::description)
				.toList();
		return new AgentContext(
				mind.identity().name(), tick,
				mind.personality().curiosity(), mind.personality().risk(),
				mind.personality().sociability(), mind.personality().ambition(),
				mind.needs().food(), mind.needs().safety(), mind.needs().social(), mind.needs().belonging(),
				memories, goalDescriptions,
				mind.possessions().stream()
						.map(p -> p.quantity() + " " + p.itemId().replaceFirst("^[^:]*:", "").replace('_', ' '))
						.toList(),
				mind.beliefs().stream()
						.skip(Math.max(0, mind.beliefs().size() - 5))
						.map(b -> b.statement())
						.toList(),
				mind.home().map(h -> "a " + h.design().name() + " it built").orElse(""),
				mind.recipeBook().recipes().stream()
						.map(r -> r.result().replaceFirst("^[^:]*:", ""))
						.filter(name -> !name.endsWith("_planks"))
						.toList()
		);
	}
}
