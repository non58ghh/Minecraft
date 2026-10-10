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

	/** The goal each agent's last reflection set (not a plan to make something), to be superseded by the next. */
	private final java.util.Map<UUID, Long> lastReflection = new java.util.HashMap<>();

	public void maybeInvoke(AgentMind mind, long tick, EventLog log, Executor mainThreadExecutor) {
		maybeInvoke(mind, tick, log, mainThreadExecutor, AgentContext.Situation::unknown, id -> null);
	}

	/**
	 * As above, with what the agent perceives where it stands (built only
	 * when it actually stops to think) and the names of the agents it knows.
	 */
	public void maybeInvoke(AgentMind mind, long tick, EventLog log, Executor mainThreadExecutor,
			java.util.function.Supplier<AgentContext.Situation> situation, java.util.function.Function<UUID, String> nameOf) {
		maybeInvoke(mind, tick, log, mainThreadExecutor, situation, nameOf, ReasoningGate.UNKNOWN);
	}

	/** As above, with whether it is night where the agent is (the turn of day or night prompts a fresh look). */
	public void maybeInvoke(AgentMind mind, long tick, EventLog log, Executor mainThreadExecutor,
			java.util.function.Supplier<AgentContext.Situation> situation, java.util.function.Function<UUID, String> nameOf,
			boolean night) {
		maybeInvoke(mind, tick, log, mainThreadExecutor, situation, nameOf, night ? ReasoningGate.NIGHT : ReasoningGate.DAY);
	}

	private void maybeInvoke(AgentMind mind, long tick, EventLog log, Executor mainThreadExecutor,
			java.util.function.Supplier<AgentContext.Situation> situation, java.util.function.Function<UUID, String> nameOf,
			int phase) {
		UUID id = mind.identity().id();
		Needs needs = mind.needs();
		String crisisNeed = needs.hasCrisis() ? needs.lowestName() : null;
		double[] needValues = {needs.food(), needs.safety(), needs.social(), needs.belonging()};
		ReasoningGate.Trigger trigger = gate.check(id, tick, crisisNeed, mind.memories().peekNextId(), needValues, phase);
		if (trigger == null) {
			return;
		}

		AgentContext context = buildContext(mind, tick, situation.get(), nameOf);
		// The memory that led the prompt: a belief formed from this pass is traced back to it.
		long promptSource = mind.memories().retrieve(tick, 1).stream().findFirst().map(MemoryEntry::id).orElse(-1L);
		String reason = switch (trigger) {
			case CRISIS -> "a " + crisisNeed + " crisis";
			case DAYBREAK -> "daybreak";
			case NIGHTFALL -> "nightfall";
			case ROUTINE -> "routine reflection";
		};
		log.append(tick, EventType.REASONING_INVOKED, List.of(id),
				mind.identity().name() + " stopped to think, prompted by " + reason + ".", List.of());

		provider.reason(context)
				.thenAccept(result -> mainThreadExecutor.execute(() -> apply(mind, result, tick, log, promptSource)));
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

	private void apply(AgentMind mind, ReasoningResult result, long tick, EventLog log, long promptSource) {
		if (result.isFailure()) {
			log.append(tick, EventType.REASONING_FAILED, List.of(mind.identity().id()),
					mind.identity().name() + "'s thinking didn't come through: " + result.failure() + ".", List.of());
			return;
		}
		result.goalDescription().map(ReasoningScheduler::sentenceCase).ifPresent(description -> {
			boolean alreadyPursuing = mind.goals().stream()
					.anyMatch(g -> g.active() && g.description().equals(description));
			if (!alreadyPursuing) {
				String target = result.targetItem().map(ReasoningScheduler::itemId).orElse(null);
				if (target != null && isFarmed(target)) {
					// Food and crops come from farming and foraging, which already work toward them: no plan needed.
					target = null;
				}
				if (target != null && !com.aicivilization.world.RecipeCatalog.isItem(target)) {
					// The model named something that doesn't exist; the agent keeps the wish but not the nonsense.
					mind.perceive(tick, "I thought of making a " + result.targetItem().get().replace('_', ' ')
							+ ", but there's no such thing.", 0.3, java.util.Set.of());
					target = null;
				}
				// A goal to have something is pursued by working through a plan for it.
				var added = mind.addGoal(tick, description, result.goalPriority(),
						target != null ? com.aicivilization.mind.IntentType.PURSUE_PLAN : result.relatedIntent().orElse(null),
						target, Math.min(64, Math.max(1, result.targetCount())));
				if (target == null && added != null) {
					// A fresh look supersedes what the last one meant to do next ("rest until dawn" doesn't
					// outlive the dawn). Plans to make something, and plans agreed with others, stand.
					Long previous = lastReflection.put(mind.identity().id(), added.id());
					if (previous != null && previous != added.id()) {
						mind.finishGoal(previous);
					}
				}
				if (target != null && !mind.recipeBook().knows(target)) {
					mind.perceive(tick, "I want " + result.targetItem().get().replace('_', ' ')
							+ ", but I don't know how to make it yet.", 0.5, java.util.Set.of());
				}
			}
		});

		result.beliefStatement().ifPresent(statement -> {
			mind.formBelief(tick, statement, result.beliefConfidence(), new Provenance.Inferred(promptSource));
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

	/** Food, crops and seeds: got by farming, hunting and foraging rather than by a plan. */
	static boolean isFarmed(String itemId) {
		return com.aicivilization.action.ItemKinds.nutrition(itemId) > 0 || itemId.endsWith("wheat")
				|| itemId.endsWith("_seeds") || itemId.endsWith("wheat_seeds");
	}

	/** "iron_pickaxe", "Iron Pickaxe" or "minecraft:iron_pickaxe" to an item id. */
	static String itemId(String name) {
		String id = name.strip().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
		return id.contains(":") ? id : "minecraft:" + id;
	}

	private static AgentContext buildContext(AgentMind mind, long tick, AgentContext.Situation situation,
			java.util.function.Function<UUID, String> nameOf) {
		mind.expireGoals(tick);
		List<String> goalDescriptions = mind.goals().stream()
				.filter(Goal::active)
				.map(Goal::description)
				.toList();
		int meals = 0;
		int woodBlocks = 0;
		for (var p : mind.possessions()) {
			meals += com.aicivilization.action.ItemKinds.nutrition(p.itemId()) * p.quantity();
			if (com.aicivilization.action.ItemKinds.isLog(p.itemId())) {
				woodBlocks += 4 * p.quantity();
			} else if (com.aicivilization.action.ItemKinds.isBuildingMaterial(p.itemId())) {
				woodBlocks += p.quantity();
			}
		}
		return new AgentContext(
				mind.identity().name(), tick,
				mind.personality().curiosity(), mind.personality().risk(),
				mind.personality().sociability(), mind.personality().ambition(),
				mind.needs().food(), mind.needs().safety(), mind.needs().social(), mind.needs().belonging(),
				memoryLines(mind.memories().all(), tick),
				goalDescriptions,
				mind.possessions().stream()
						.map(p -> p.quantity() + " " + p.itemId().replaceFirst("^[^:]*:", "").replace('_', ' '))
						.toList(),
				mind.beliefs().stream()
						.skip(Math.max(0, mind.beliefs().size() - 5))
						.map(b -> b.statement())
						.toList(),
				homeLine(mind, woodBlocks, tick),
				canMake(mind),
				situation,
				meals / 5,
				peopleLines(mind, tick, nameOf),
				goalLines(mind, tick),
				setbacks(mind.memories().all(), tick)
		);
	}

	private static List<String> canMake(AgentMind mind) {
		List<String> known = new java.util.ArrayList<>(java.util.stream.Stream.concat(
						mind.recipeBook().recipes().stream().map(r -> r.result()),
						mind.recipeBook().sources().stream().map(s -> s.item()))
				.map(id -> id.replaceFirst("^[^:]*:", ""))
				.filter(name -> !name.endsWith("_planks") && !name.endsWith("_log") || name.equals("oak_log"))
				.distinct()
				.map(name -> name.replace('_', ' '))
				.toList());
		// What it has learned about saplings, in its own terms (nobody starts knowing it).
		var book = mind.recipeBook();
		if (book.knowsPractice(com.aicivilization.mind.RecipeBook.REPLANTING)) {
			known.add("saplings (planted, they grow into trees)");
		} else if (book.heardOfPractice(com.aicivilization.mind.RecipeBook.REPLANTING)) {
			known.add("saplings (heard they grow into trees if planted; not seen it)");
		}
		return known;
	}

	/** Chores that happen all day; folded into one line per day so they don't crowd out what matters. */
	private static final java.util.regex.Pattern CHORE = java.util.regex.Pattern.compile(
			"^I (tended|harvested|ate some|chopped|planted|picked|dug farmland|mined some|cooked|made bread|hunted|fed|explored an unfamiliar|cut grass)");
	/** How far back it looks, and how many notable things it brings up. */
	private static final long MEMORY_WINDOW_TICKS = 48_000;
	private static final int NOTABLE_MEMORIES = 10;

	/**
	 * Recent memories, oldest first, labelled by day: the most recent notable
	 * ones (repeats shown once with a count), and each day's chores as one
	 * line ("Today: tended my field ×4, harvested wheat ×2.").
	 */
	static List<String> memoryLines(List<MemoryEntry> all, long tick) {
		record Line(long tick, String text) {
		}
		// Notable memories: the latest occurrence of each, with how many times it came up.
		java.util.Map<String, Long> lastTick = new java.util.HashMap<>();
		java.util.Map<String, Integer> times = new java.util.HashMap<>();
		// Chores: per day, what and how often, in the order first done.
		java.util.TreeMap<Long, java.util.LinkedHashMap<String, Integer>> chores = new java.util.TreeMap<>();
		for (MemoryEntry m : all) {
			if (tick - m.tick() > MEMORY_WINDOW_TICKS) {
				continue;
			}
			String text = m.description().strip();
			if (CHORE.matcher(text).find()) {
				String what = text.substring(2).replaceAll("\\.$", "").replaceAll(" and took \\d+ logs?$", "");
				chores.computeIfAbsent(Math.floorDiv(m.tick(), 24000L), d -> new java.util.LinkedHashMap<>()).merge(what, 1, Integer::sum);
				continue;
			}
			String key = AgentContext.dayLabel(m.tick(), tick) + ": " + text;
			lastTick.merge(key, m.tick(), Math::max);
			times.merge(key, 1, Integer::sum);
		}
		List<Line> out = new java.util.ArrayList<>();
		lastTick.entrySet().stream()
				.sorted(java.util.Map.Entry.comparingByValue())
				.skip(Math.max(0, lastTick.size() - NOTABLE_MEMORIES))
				.forEach(e -> {
					int n = times.get(e.getKey());
					out.add(new Line(e.getValue(), n > 1 ? e.getKey() + " (" + n + " times)" : e.getKey()));
				});
		chores.forEach((day, items) -> {
			StringBuilder line = new StringBuilder(AgentContext.dayLabel(day * 24000L, tick)).append(": ");
			int i = 0;
			for (var e : items.entrySet()) {
				line.append(i++ == 0 ? "" : ", ").append(e.getKey()).append(e.getValue() > 1 ? " \u00d7" + e.getValue() : "");
			}
			// A day's chores sit after that day's notable things.
			out.add(new Line(day * 24000L + 23999, line.append('.').toString()));
		});
		out.sort(java.util.Comparator.comparingLong(Line::tick));
		return out.stream().map(Line::text).toList();
	}

	/** Things that went wrong in the last day, newest last ("I gave up on making oak log; I couldn't get 20 oak log."). */
	static List<String> setbacks(List<MemoryEntry> all, long tick) {
		return all.stream()
				.filter(m -> tick - m.tick() <= 24_000)
				.filter(m -> m.description().contains("gave up") || m.description().contains("couldn't")
						|| m.description().contains("but didn't have it") || m.description().contains("never gave it"))
				.sorted(java.util.Comparator.comparingLong(MemoryEntry::tick))
				.map(MemoryEntry::description)
				.distinct()
				.reduce(new java.util.ArrayList<String>(), (acc, d) -> {
					acc.add(d);
					while (acc.size() > 3) {
						acc.remove(0);
					}
					return acc;
				}, (a, b) -> a);
	}

	/** "Find Ilse and share bread. Since day 5; worked at it twice." */
	private static List<String> goalLines(AgentMind mind, long tick) {
		List<String> out = new java.util.ArrayList<>();
		for (Goal g : mind.goals()) {
			if (!g.active()) {
				continue;
			}
			String d = g.description().strip();
			StringBuilder line = new StringBuilder(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
			line.append(". Since ").append(AgentContext.dayLabel(g.createdTick(), tick).toLowerCase(java.util.Locale.ROOT));
			if (g.hasTarget()) {
				line.append("; you have ").append(mind.countOf(g.targetItem())).append(" of ").append(g.targetCount());
			} else if (g.progress() > 0) {
				line.append("; worked at it ").append(g.progress() == 1 ? "once" : g.progress() == 2 ? "twice" : g.progress() + " times");
			} else {
				line.append("; not started");
			}
			out.add(line.append('.').toString());
		}
		return out;
	}

	/** The people it knows, closest first: how it feels about them and when it last saw them. */
	private static List<String> peopleLines(AgentMind mind, long tick, java.util.function.Function<UUID, String> nameOf) {
		return mind.relationships().asMap().entrySet().stream()
				.filter(e -> nameOf.apply(e.getKey()) != null)
				.sorted(java.util.Comparator.comparingDouble(
						(java.util.Map.Entry<UUID, com.aicivilization.mind.RelationshipData> e) -> e.getValue().affinity()).reversed())
				.limit(6)
				.map(e -> {
					var r = e.getValue();
					String feeling = r.affinity() > 0.5 ? "a good friend" : r.affinity() > 0.15 ? "someone you like"
							: r.affinity() < -0.3 ? "someone you dislike" : r.affinity() < -0.05 ? "someone you're wary of"
							: "an acquaintance";
					String trust = r.trust() > 0.5 ? ", and you trust them" : "";
					String seen = r.lastSeenTick() < 0 ? "Not seen lately." : "Last seen " + seenLabel(r.lastSeenTick(), tick) + ".";
					return nameOf.apply(e.getKey()) + ": " + feeling + trust + ". " + seen;
				})
				.toList();
	}

	private static String seenLabel(long seen, long tick) {
		if (tick - seen < 1200) {
			return "just now";
		}
		String day = AgentContext.dayLabel(seen, tick);
		return day.equals("Today") ? "earlier today" : day.equals("Yesterday") ? "yesterday" : "on day " + Math.floorDiv(seen, 24000L);
	}

	/** Its home, or how far it is from having one. */
	private static String homeLine(AgentMind mind, int woodBlocks, long tick) {
		if (mind.home().isPresent()) {
			return "you live in the " + mind.home().get().design().kind() + " you built.";
		}
		if (mind.project().isPresent()) {
			var p = mind.project().get();
			return "you're building a " + p.design().kind() + " together with " + p.partnerName() + "; you carry wood for about "
					+ woodBlocks + " of its " + p.design().solids().size() + " blocks.";
		}
		if (mind.buildingSite().isPresent()) {
			return "you've started building your " + mind.buildingSite().get().design().kind() + "; you carry wood for about "
					+ woodBlocks + " more blocks.";
		}
		var design = mind.designToBuild().design();
		return "none yet. You have a " + design.kind() + " in mind, needing about " + design.solids().size()
				+ " blocks; you carry wood for about " + woodBlocks + ".";
	}
}
