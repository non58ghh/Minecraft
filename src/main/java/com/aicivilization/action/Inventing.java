package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Goal;
import com.aicivilization.mind.Home;
import com.aicivilization.mind.IntentType;
import com.aicivilization.mind.Invention;
import com.aicivilization.population.PopulationRegistry;
import com.aicivilization.reasoning.InventBrief;
import com.aicivilization.reasoning.ReasoningProvider;
import com.aicivilization.world.RecipeCatalog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Agents thinking up devices of their own, and seeing them through. Now and
 * then one with a reason to (redstone in hand, a pickaxe that could dig it,
 * a device it has seen work) turns over what has been going wrong for it; the
 * strong model, given only the parts it knows and how each behaves, decides
 * whether a device would help and if so designs one. The parts it lacks
 * become ordinary goals; once it has them and is home, it builds the device
 * and tries it (see {@link Tinkering}). Ideas that sit too long unbuilt are
 * given up.
 */
public final class Inventing {

	public static final int CHECK_EVERY_TICKS = 200;
	/** One idea at most per agent per game day. */
	static final long COOLDOWN_TICKS = 24000;
	/** An idea not built within three game days is let go. */
	static final long GIVE_UP_TICKS = 3 * 24000;
	/** Close enough to home to build there. */
	static final int AT_HOME = 12;

	private static final Map<UUID, Long> lastIdea = new HashMap<>();
	private static final Set<UUID> thinking = new HashSet<>();
	private static long day = -1;
	private static int ideasToday;

	private Inventing() {
	}

	/** Every {@link #CHECK_EVERY_TICKS}: moves planned devices along, and lets one agent think up a new one if it's due. */
	public static void tick(ServerLevel world, ReasoningProvider provider, int perDay, long tick) {
		if (perDay <= 0 || provider == null) {
			return;
		}
		if (tick / 24000 != day) {
			day = tick / 24000;
			ideasToday = 0;
		}
		EventLog log = EventLog.get(world);
		long timeOfDay = world.getOverworldClockTime() % 24000L;
		boolean daytime = timeOfDay >= 1000 && timeOfDay < 11500;
		List<AgentMind> due = new ArrayList<>();
		for (AgentMind mind : PopulationRegistry.get(world).population().allMinds()) {
			if (!mind.isAlive() || mind.home().isEmpty() || !(world.getEntity(mind.identity().id()) instanceof AgentEntity body)) {
				continue;
			}
			if (mind.plannedInvention() != null) {
				carryOn(body, world, mind, mind.home().get(), daytime, tick, log);
			} else if (daytime && !thinking.contains(mind.identity().id()) && !mind.inDanger() && hasReason(mind)
					&& tick - lastIdea.getOrDefault(mind.identity().id(), Long.MIN_VALUE / 2) >= COOLDOWN_TICKS) {
				due.add(mind);
			}
		}
		if (!due.isEmpty() && thinking.isEmpty() && ideasToday < perDay) {
			// The curious turn things over more often.
			AgentMind pick = due.get(0);
			double best = -1;
			for (AgentMind m : due) {
				double roll = world.getRandom().nextDouble() * (0.3 + m.personality().curiosity());
				if (roll > best) {
					best = roll;
					pick = m;
				}
			}
			think(world, provider, pick, tick, log);
		}
	}

	/** Redstone in hand, a pickaxe good enough to dig it, or a device it's seen work. */
	static boolean hasReason(AgentMind mind) {
		return mind.countOf("minecraft:redstone") > 0
				|| Crafting.best(mind, Crafting.Tool.PICKAXE).map(Crafting::isIron).orElse(false)
				|| mind.inventions().stream().anyMatch(k -> k.works() && !k.how().equals("invented"));
	}

	private static void think(ServerLevel world, ReasoningProvider provider, AgentMind mind, long tick, EventLog log) {
		UUID id = mind.identity().id();
		Home home = mind.home().get();
		BlockPos origin = Tinkering.originOf(home);
		boolean door = IronDoor.door(world, origin, home.design()).isPresent();
		String homeLine = "a " + home.design().name() + (door ? " with a door" : " with an open doorway")
				+ (com.aicivilization.behavior.NeedsDrivenGoal.homeLit(world, home) ? ", lit outside." : ", dark outside at night.");
		InventBrief brief = InventBrief.of(mind, RecipeCatalog.get(), tick, homeLine);
		thinking.add(id);
		lastIdea.put(id, tick);
		ideasToday++;
		var server = world.getServer();
		provider.invent(brief)
				.exceptionally(ex -> Optional.empty())
				.thenAccept(reply -> server.execute(() -> {
					thinking.remove(id);
					reply.ifPresent(json -> consider(mind, json, world.getGameTime(), log));
				}));
	}

	/** What came back: no need for a device, an idea that doesn't hold together, or a plan to build one. */
	static void consider(AgentMind mind, String json, long tick, EventLog log) {
		JsonObject o;
		try {
			o = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			return;
		}
		if (!o.has("invent") || !o.get("invent").getAsBoolean() || !mind.isAlive()) {
			return;
		}
		Invention.Parsed parsed = Invention.parse(o);
		String problem = o.has("problem") && o.get("problem").isJsonPrimitive() ? o.get("problem").getAsString().strip() : "";
		if (parsed.invention().isEmpty()) {
			mind.perceive(tick, "I had an idea for a device, but couldn't work out how it would go together: " + parsed.problem() + ".",
					0.35, Set.of());
			return;
		}
		Invention idea = parsed.invention().get();
		mind.planInvention(idea, tick);
		List<String> needs = new ArrayList<>();
		idea.materials().forEach((item, n) -> needs.add(n + " " + ItemKinds.displayName(item)));
		mind.perceive(tick, "I had an idea" + (problem.isEmpty() ? "" : " (" + problem + ")") + ": " + idea.name() + ", to "
				+ Tinkering.lower(idea.purpose()) + ". It needs " + String.join(", ", needs) + ".", 0.65, Set.of());
		log.append(tick, EventType.DECISION, List.of(mind.identity().id()), mind.identity().name() + " came up with an idea for a device: "
				+ idea.name() + ", to " + Tinkering.lower(idea.purpose()) + ".", List.of(),
				problem.isEmpty() ? List.of() : List.of("The problem: " + problem, "Expected: " + idea.expect()));
	}

	/** A device it means to build: gathers what's missing, builds it once home with everything, or gives up on it. */
	private static void carryOn(AgentEntity body, ServerLevel world, AgentMind mind, Home home, boolean daytime, long tick, EventLog log) {
		Invention idea = mind.plannedInvention();
		if (Tinkering.trying(mind.identity().id())) {
			return;
		}
		if (tick - mind.plannedInventionTick() > GIVE_UP_TICKS) {
			giveUp(mind, idea, "it sat too long without the parts coming together", tick, log);
			return;
		}
		Map<String, Integer> missing = Tinkering.missing(mind, idea);
		if (!missing.isEmpty()) {
			String item = missing.keySet().iterator().next();
			int want = idea.materials().get(item);
			boolean already = mind.goals().stream().anyMatch(g -> g.active() && item.equals(g.targetItem()));
			if (!already) {
				mind.addGoal(tick, "get " + want + " " + ItemKinds.displayName(item) + " for my " + idea.name(), 0.7,
						IntentType.PURSUE_PLAN, item, want);
			}
			return;
		}
		BlockPos origin = Tinkering.originOf(home);
		if (!daytime || body.blockPosition().distSqr(origin) > AT_HOME * AT_HOME) {
			return;
		}
		Optional<Tinkering.Site> site = Tinkering.siteFor(world, origin, home.design(), idea);
		if (site.isEmpty()) {
			giveUp(mind, idea, "there's no room for it " + (idea.anchor() == Invention.Anchor.DOOR ? "at my door" : "near my home"),
					tick, log);
			return;
		}
		Optional<String> problem = Tinkering.buildAndTry(body, world, mind, idea, site.get(), false, tick, log);
		if (problem.isPresent()) {
			giveUp(mind, idea, problem.get(), tick, log);
			return;
		}
		mind.planInvention(null, tick);
	}

	private static void giveUp(AgentMind mind, Invention idea, String why, long tick, EventLog log) {
		mind.planInvention(null, tick);
		for (Goal g : mind.goals()) {
			if (g.active() && g.description().endsWith("for my " + idea.name())) {
				mind.finishGoal(g.id());
			}
		}
		mind.perceive(tick, "I gave up on building " + idea.name() + ": " + why + ".", 0.4, Set.of());
		log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
				mind.identity().name() + " gave up on building " + idea.name() + ": " + why + ".", List.of());
	}
}
