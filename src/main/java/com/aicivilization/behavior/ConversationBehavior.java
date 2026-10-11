package com.aicivilization.behavior;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.Cause;
import com.aicivilization.events.CauseType;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.action.ItemKinds;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.IntentType;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Places;
import com.aicivilization.mind.RelationshipData;

import com.aicivilization.mind.Provenance;
import com.aicivilization.mind.RecipeBook;
import com.aicivilization.reasoning.Dialogue;
import com.aicivilization.reasoning.DialogueBrief;
import com.aicivilization.reasoning.ReasoningProvider;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates one interaction between two embodied agents. Proximity (the
 * caller already knows the two entities are close) only makes interaction
 * <em>possible</em> — this is where each mind's own needs/personality/
 * relationship-toward-the-other decide whether anything happens, and if
 * so, for what purpose: share news, pass the time, ask for help, or
 * trade (see {@link TradeBehavior}). A pair that has just talked won't
 * strike up the same conversation again for half a minute, and news is only
 * first-hand and only told to someone once, so gossip doesn't loop.
 */
public final class ConversationBehavior {

	private enum Purpose {
		EXCHANGE_INFORMATION,
		SOCIALIZE,
		REQUEST_HELP,
		OFFER_TRADE,
		AVOID
	}

	/** A child this hungry is fed by a parent who will. */
	private static final double CHILD_HUNGRY = 0.6;
	/** A given pair strikes up a conversation at most this often (half a minute). */
	private static final long PAIR_COOLDOWN_TICKS = 600;
	/** Last tick each (speaker, listener) pair talked. Not saved; it only paces conversation. */
	private static final Map<String, Long> LAST_TALK = new HashMap<>();
	/** What each speaker has already told each listener (by wording, so the same news isn't retold). Not saved. */
	private static final Map<String, Set<Long>> TOLD = new HashMap<>();

	/** Below this, a memory is routine (a meal, a chore): not something to bring up with anyone. */
	private static final double NEWSWORTHY = 0.5;
	/** What an agent takes away from a written conversation: worth keeping, not worth spreading. */
	private static final double RECOLLECTION_IMPORTANCE = 0.45;
	/** A walk that found nothing. Older saves rated it higher than it deserves. */
	private static final String AIMLESS_WALK = "I explored an unfamiliar area.";

	private static boolean isNews(MemoryEntry m) {
		return m.importance() >= NEWSWORTHY && !m.description().equals(AIMLESS_WALK);
	}

	/** Neighbours chatting every half minute isn't news: the same pair's small talk makes the timeline at most this often. */
	private static final long SMALL_TALK_NEWS_TICKS = 6000;
	/** Last tick each pair's small talk was logged. Not saved. */
	private static final Map<String, Long> LAST_SMALL_TALK_LOGGED = new HashMap<>();

	private ConversationBehavior() {
	}

	/** Writes conversations out (an LLM), if there is one; set once at startup. */
	private static ReasoningProvider writer;
	private static Executor mainThread;
	private static long dialogueIntervalTicks;
	private static long lastDialogueTick = Long.MIN_VALUE / 2;
	private static boolean dialoguePending;

	public static void useWriter(ReasoningProvider provider, Executor serverThread, long intervalTicks) {
		writer = provider;
		mainThread = serverThread;
		dialogueIntervalTicks = intervalTicks;
	}

	private static void logSmallTalk(AgentEntity selfEntity, AgentMind self, AgentMind other, long tick, EventLog log) {
		UUID a = self.identity().id();
		UUID b = other.identity().id();
		String pair = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
		Long last = LAST_SMALL_TALK_LOGGED.get(pair);
		if (last != null && tick - last < SMALL_TALK_NEWS_TICKS) {
			return;
		}
		if (LAST_SMALL_TALK_LOGGED.size() > 4096) {
			LAST_SMALL_TALK_LOGGED.clear();
		}
		LAST_SMALL_TALK_LOGGED.put(pair, tick);
		List<Cause> causes = smallTalkCauses(self, other);
		if (writer != null && dialogueIntervalTicks > 0 && !dialoguePending && tick - lastDialogueTick >= dialogueIntervalTicks) {
			lastDialogueTick = tick;
			dialoguePending = true;
			DialogueBrief brief = new DialogueBrief(speaker(self, other, tick), speaker(other, self, tick),
					timeOfDay(((net.minecraft.server.level.ServerLevel) selfEntity.level()).getOverworldClockTime() % 24000L));
			writer.converse(brief)
					.exceptionally(ex -> Optional.empty())
					.thenAccept(dialogue -> mainThread.execute(() -> {
						dialoguePending = false;
						if (dialogue.isPresent() && self.isAlive() && other.isAlive()) {
							logDialogue(self, other, dialogue.get(), tick, causes, log);
						} else {
							logPlainSmallTalk(self, other, tick, causes, log);
						}
					}));
			return;
		}
		logPlainSmallTalk(self, other, tick, causes, log);
	}

	private static void logDialogue(AgentMind self, AgentMind other, Dialogue dialogue, long tick, List<Cause> causes,
			EventLog log) {
		UUID a = self.identity().id();
		UUID b = other.identity().id();
		// Written by the model, not seen: kept as the agent's own take on the talk (Inferred), and below
		// NEWSWORTHY so it isn't passed on to others as if it were first-hand news.
		if (!dialogue.firstRemembers().isEmpty()) {
			self.inferMemory(tick, dialogue.firstRemembers(), RECOLLECTION_IMPORTANCE, Set.of(b), -1);
		}
		if (!dialogue.secondRemembers().isEmpty()) {
			other.inferMemory(tick, dialogue.secondRemembers(), RECOLLECTION_IMPORTANCE, Set.of(a), -1);
		}
		var talk = log.append(tick, EventType.CONVERSATION, List.of(a, b),
				self.identity().name() + " and " + other.identity().name() + " talked about " + dialogue.topic() + ".",
				causes, dialogue.lines());
		Cause agreedThen = Cause.event(talk.id(), "agreed when they talked about " + dialogue.topic());
		for (Dialogue.Agreement agreement : dialogue.agreements()) {
			switch (agreement.kind()) {
				case GIVE -> keepPromise(agreement.first() ? self : other, agreement.first() ? other : self, agreement, tick,
						agreedThen, log);
				case PLAN -> {
					if (agreement.both() && "BUILD_SHELTER".equals(agreement.activity())) {
						// Both taking on a build: that is building together, a shared site and a shared home,
						// not two separate goals that send them off to find each other and confirm it.
						CoBuilding.agree(self, other, tick, log);
					}
					if (agreement.both() || agreement.first()) {
						takeOnPlan(self, other, agreement, tick, agreedThen, log);
					}
					if (agreement.both() || !agreement.first()) {
						takeOnPlan(other, self, agreement, tick, agreedThen, log);
					}
				}
				case BUILD_TOGETHER -> CoBuilding.agree(self, other, tick, log);
			}
		}
	}

	/** Most of one thing handed over at once: a promise, not a moving house. */
	private static final int MAX_GIFT = 16;

	/** A gift or half a swap agreed in conversation, handed over there and then if the giver really has it. */
	private static void keepPromise(AgentMind giver, AgentMind receiver, Dialogue.Agreement agreement, long tick,
			Cause agreedThen, EventLog log) {
		String item = agreement.item();
		int count = Math.min(Math.min(agreement.count(), giver.countOf(item)), MAX_GIFT);
		String giverName = giver.identity().name();
		String receiverName = receiver.identity().name();
		if (count <= 0 || TradeBehavior.keepsBack(giver, item) || !giver.takeItem(item, count)) {
			// Said it, couldn't do it: both notice.
			giver.perceive(tick, "I promised " + receiverName + " some " + ItemKinds.displayName(item)
					+ " but didn't have it to give.", 0.4, Set.of(receiver.identity().id()));
			receiver.perceive(tick, giverName + " promised me some " + ItemKinds.displayName(item) + " but never gave it.",
					0.45, Set.of(giver.identity().id()));
			receiver.relationships().with(giver.identity().id()).recordConversation(tick, -0.02, -0.05);
			return;
		}
		receiver.receiveItem(tick, item, count);
		String what = count + " " + ItemKinds.displayName(item);
		giver.perceive(tick, "I gave " + receiverName + " " + what + ", as I said I would.", 0.45,
				Set.of(receiver.identity().id()));
		receiver.perceive(tick, giverName + " gave me " + what + ", as they said they would.", 0.55,
				Set.of(giver.identity().id()));
		// Keeping one's word builds trust.
		receiver.relationships().with(giver.identity().id()).recordConversation(tick, 0.05, 0.06);
		log.append(tick, EventType.ACTION, List.of(giver.identity().id(), receiver.identity().id()),
				giverName + " gave " + receiverName + " " + what + ", as they'd agreed.", List.of(agreedThen));
	}

	/** A plan made in conversation becomes a goal, which weighs on what the agent decides to do next. */
	private static void takeOnPlan(AgentMind mind, AgentMind with, Dialogue.Agreement agreement, long tick, Cause agreedThen,
			EventLog log) {
		IntentType intent;
		try {
			intent = IntentType.valueOf(agreement.activity());
		} catch (IllegalArgumentException e) {
			return;
		}
		String goal = agreement.goal();
		mind.addGoal(tick, goal, PLAN_PRIORITY, intent);
		mind.perceive(tick, "I agreed with " + with.identity().name() + " to " + goal + ".", 0.55, Set.of(with.identity().id()));
		log.append(tick, EventType.DECISION, List.of(mind.identity().id(), with.identity().id()),
				mind.identity().name() + " means to " + goal + ", as agreed with " + with.identity().name() + ".",
				List.of(agreedThen));
	}

	/** A plan agreed face to face counts for a good deal, but survival still comes first. */
	private static final double PLAN_PRIORITY = 0.7;

	private static void logPlainSmallTalk(AgentMind self, AgentMind other, long tick, List<Cause> causes, EventLog log) {
		UUID a = self.identity().id();
		UUID b = other.identity().id();
		// Without a writer: what each brought up, the thing on its mind most of what it lived through itself lately.
		String selfSaid = smallTalkTopic(self, b, tick);
		String otherSaid = smallTalkTopic(other, a, tick);
		if (selfSaid == null && otherSaid == null) {
			// Chatting about nothing much: good for them, not news.
			return;
		}
		List<String> lines = new java.util.ArrayList<>();
		if (selfSaid != null) {
			lines.add(self.identity().name() + ": " + selfSaid);
		}
		if (otherSaid != null) {
			lines.add(other.identity().name() + ": " + otherSaid);
		}
		log.append(tick, EventType.CONVERSATION, List.of(a, b), self.identity().name() + " and " + other.identity().name()
				+ " talked for a while.", causes, lines);
	}

	private static List<Cause> smallTalkCauses(AgentMind self, AgentMind other) {
		RelationshipData bond = self.relationships().with(other.identity().id());
		return List.of(
				new Cause(CauseType.NEED_STATE, "social", self.identity().name() + "'s social "
						+ String.format(Locale.ROOT, "%.2f", self.needs().social())),
				new Cause(CauseType.NEED_STATE, "social", other.identity().name() + "'s social "
						+ String.format(Locale.ROOT, "%.2f", other.needs().social())),
				new Cause(CauseType.FACTOR, "affinity", self.identity().name() + "'s liking for " + other.identity().name() + " "
						+ String.format(Locale.ROOT, "%.2f", bond.affinity())));
	}

	/** One side of a conversation, as that agent knows itself and the other. */
	static DialogueBrief.Speaker speaker(AgentMind mind, AgentMind other, long tick) {
		return speaker(mind, other.identity().id(), other.identity().name(), tick);
	}

	/** As above, toward anyone it may know by id (a player too). */
	static DialogueBrief.Speaker speaker(AgentMind mind, UUID otherId, String otherName, long tick) {
		var p = mind.personality();
		List<String> traits = new java.util.ArrayList<>();
		traits.add(p.sociability() > 0.6 ? "warm and talkative" : p.sociability() < 0.35 ? "reserved" : "friendly enough");
		if (p.curiosity() > 0.6) {
			traits.add("curious");
		}
		if (p.ambition() > 0.6) {
			traits.add("driven");
		}
		traits.add(p.risk() > 0.6 ? "bold" : p.risk() < 0.35 ? "cautious" : "steady");
		var needs = mind.needs();
		StringBuilder situation = new StringBuilder();
		situation.append(needs.food() < 0.3 ? "Very hungry. " : needs.food() < 0.55 ? "A bit hungry. " : "Well fed. ");
		int meals = TradeBehavior.foodMeals(mind);
		situation.append(meals == 0 ? "Carrying no food. " : "Carrying about " + meals + " meals of food. ");
		situation.append(mind.home().map(h -> "Lives in a " + h.design().kind() + " they built. ").orElse(
				mind.project().map(pr -> "Building a home together with " + pr.partnerName() + ". ")
						.orElse(mind.buildingSite().isPresent() ? "Building a home. " : "Has no home yet. ")));
		if (needs.safety() < 0.3) {
			situation.append("Feels unsafe. ");
		}
		if (needs.social() < 0.3) {
			situation.append("Has been lonely. ");
		}
		RelationshipData bond = mind.relationships().with(otherId);
		String feeling = bond.affinity() > 0.5 ? "a good friend" : bond.affinity() > 0.15 ? "someone they like"
				: bond.affinity() < -0.3 ? "someone they dislike" : bond.affinity() < -0.05 ? "someone they're wary of"
				: "an acquaintance";
		String trust = bond.trust() > 0.5 ? ", and trusts them" : bond.trust() < 0.1 ? ", but doesn't know if they can be trusted" : "";
		// What's really on its mind: the notable things first, routine (meals, chores) only to fill in.
		List<MemoryEntry> recent = mind.memories().retrieve(tick, 10);
		List<String> experiences = java.util.stream.Stream.concat(
						recent.stream().filter(ConversationBehavior::isNews),
						recent.stream().filter(m -> !isNews(m) && m.importance() >= 0.3 && !m.description().equals(AIMLESS_WALK)))
				.map(MemoryEntry::description)
				.distinct()
				.limit(5)
				.toList();
		String carrying = mind.possessions().stream()
				.filter(item -> item.quantity() > 0)
				.sorted(java.util.Comparator.comparingInt(com.aicivilization.mind.Possession::quantity).reversed())
				.limit(10)
				.map(item -> item.quantity() + " " + item.itemId())
				.collect(java.util.stream.Collectors.joining(", "));
		return new DialogueBrief.Speaker(mind.identity().name(), "Temperament: " + String.join(", ", traits) + ".",
				situation.toString().strip(), carrying, experiences, "Sees " + otherName + " as " + feeling + trust + ".");
	}

	static String timeOfDay(long dayTime) {
		if (dayTime < 1000 || dayTime >= 23000) {
			return "dawn";
		}
		if (dayTime < 6000) {
			return "morning";
		}
		if (dayTime < 11000) {
			return "afternoon";
		}
		if (dayTime < 13000) {
			return "dusk";
		}
		return "night";
	}

	/** Something it did or saw itself, recently and worth mentioning (not the listener's own doings). */
	private static String smallTalkTopic(AgentMind speaker, UUID listener, long tick) {
		for (MemoryEntry m : speaker.memories().retrieve(tick, 6)) {
			if (!(m.provenance() instanceof Provenance.Told) && !m.participants().contains(listener)
					&& isNews(m)) {
				return m.description();
			}
		}
		return null;
	}

	/** Whether these two talked within the last half minute. */
	public static boolean recentlyTalked(UUID a, UUID b, long tick) {
		Long last = LAST_TALK.get(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a);
		return last != null && tick - last < PAIR_COOLDOWN_TICKS;
	}

	public static void attempt(AgentEntity selfEntity, AgentEntity otherEntity, long tick, EventLog log, double purposeRoll) {
		AgentMind self = selfEntity.mind();
		AgentMind other = otherEntity.mind();
		if (self == null || other == null || !self.isAlive() || !other.isAlive()) {
			return;
		}

		RelationshipData relationship = self.relationships().with(other.identity().id());
		double desire = (1.0 - self.needs().social()) * 0.6
				+ self.personality().sociability() * 0.3
				+ (relationship.affinity() + 1.0) / 2.0 * 0.1;

		if (desire < 0.2) {
			return; // AVOID: proximity alone did not produce an interaction.
		}

		// Either one starting it counts: the same two don't talk twice in half a minute.
		UUID a = self.identity().id();
		UUID b = other.identity().id();
		String pair = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
		Long last = LAST_TALK.get(pair);
		if (last != null && tick - last < PAIR_COOLDOWN_TICKS) {
			// They've only just talked; being in each other's company still helps a little.
			self.needs().adjustSocial(0.05);
			return;
		}
		if (LAST_TALK.size() > 4096) {
			LAST_TALK.clear();
		}
		LAST_TALK.put(pair, tick);

		// A hungry person in front of someone with plenty: in conversation it shows, and a generous one shares.
		if (TradeBehavior.inNeed(other) && TradeBehavior.hasFoodToSpare(self)
				&& purposeRoll < 0.4 + self.personality().sociability() * 0.5) {
			TradeBehavior.offerFood(self, other, tick, log);
			self.needs().adjustSocial(0.08);
			other.needs().adjustSocial(0.08);
			return;
		}
		// The same the other way round: a hungry one comes up to someone with plenty, and it shows.
		if (TradeBehavior.inNeed(self) && TradeBehavior.hasFoodToSpare(other)
				&& purposeRoll < 0.4 + other.personality().sociability() * 0.5) {
			TradeBehavior.offerFood(other, self, tick, log);
			self.needs().adjustSocial(0.08);
			other.needs().adjustSocial(0.08);
			return;
		}
		// A parent with a hungry child in front of it: whether it feeds it is down to its nature and its fondness.
		for (AgentMind[] family : new AgentMind[][] {{self, other}, {other, self}}) {
			AgentMind parent = family[0], young = family[1];
			if (young.isChild(tick) && young.parents().contains(parent.identity().id())
					&& young.needs().food() < CHILD_HUNGRY && TradeBehavior.foodMeals(parent) > 0
					&& purposeRoll < 0.3 + parent.personality().sociability() * 0.4
							+ Math.max(0, parent.relationships().with(young.identity().id()).affinity()) * 0.3
					&& TradeBehavior.offerFood(parent, young, tick, log)) {
				self.needs().adjustSocial(0.08);
				other.needs().adjustSocial(0.08);
				return;
			}
		}
		if (!self.isChild(tick) && !other.isChild(tick) && CoBuilding.talk(self, other, tick, purposeRoll, log)) {
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.08);
			return;
		}
		Purpose purpose = choosePurpose(purposeRoll, relationship, TradeBehavior.inNeed(self), !self.possessions().isEmpty());
		switch (purpose) {
			case EXCHANGE_INFORMATION -> exchangeInformation(self, other, selfEntity, otherEntity, tick, log);
			case SOCIALIZE -> {
				self.needs().adjustSocial(0.15);
				other.needs().adjustSocial(0.1);
				relationship.recordConversation(tick, 0.03, 0.01);
				logSmallTalk(selfEntity, self, other, tick, log);
			}
			case REQUEST_HELP -> {
				TradeBehavior.requestHelp(self, other, tick, log);
				self.needs().adjustSocial(0.08);
				other.needs().adjustSocial(0.05);
			}
			case OFFER_TRADE -> {
				TradeBehavior.offerTrade(self, other, tick, log);
				self.needs().adjustSocial(0.08);
				other.needs().adjustSocial(0.05);
			}
			case AVOID -> {
			}
		}
	}

	/**
	 * What the conversation is for. Someone in need asks for help far more
	 * often; someone with things to offer may propose a trade; trust makes
	 * sharing news more likely.
	 */
	private static Purpose choosePurpose(double r, RelationshipData relationship, boolean inNeed, boolean hasGoods) {
		double info = 0.4 + relationship.trust() * 0.1;
		double social = 0.25;
		double help = inNeed ? 0.35 : 0.03;
		double trade = hasGoods ? 0.2 : 0.0;
		double avoid = 0.03;
		double roll = r * (info + social + help + trade + avoid);
		if ((roll -= info) < 0) {
			return Purpose.EXCHANGE_INFORMATION;
		}
		if ((roll -= social) < 0) {
			return Purpose.SOCIALIZE;
		}
		if ((roll -= help) < 0) {
			return Purpose.REQUEST_HELP;
		}
		if ((roll -= trade) < 0) {
			return Purpose.OFFER_TRADE;
		}
		return Purpose.AVOID;
	}

	/** Places worth telling someone about, and how to say what's there. */
	private static final Map<Places.Kind, String> WORTH_TELLING = Map.of(
			Places.Kind.WOODS, "there are trees",
			Places.Kind.ANIMALS, "they saw animals",
			Places.Kind.STUCK, "they got stuck in a spot");
	/** A place this close is in sight of them both: nothing to tell. */
	private static final int TELL_OF_PLACES_BEYOND = 24;

	/**
	 * Tells the other of one place it has seen with its own eyes (never one
	 * it was only told of), picked at random among those it hasn't told them
	 * of: where it saw trees or animals, or where it got stuck. To the
	 * listener it's hearsay, a place to try; it may be wrong by the time
	 * they get there. Returns false if there was nothing new to tell.
	 */
	static boolean tellOfPlace(AgentMind self, AgentMind other, AgentEntity selfEntity, long tick, EventLog log) {
		BlockPos here = selfEntity.blockPosition();
		List<Places.Place> known = new java.util.ArrayList<>();
		for (Places.Kind kind : WORTH_TELLING.keySet()) {
			for (Places.Place p : self.places().of(kind, tick)) {
				if (!p.heard() && p.distSqr(here.getX(), p.y(), here.getZ()) > (long) TELL_OF_PLACES_BEYOND * TELL_OF_PLACES_BEYOND) {
					known.add(p);
				}
			}
		}
		if (known.isEmpty()) {
			return false;
		}
		java.util.Collections.shuffle(known, new java.util.Random(selfEntity.getRandom().nextLong()));
		Set<Long> alreadyTold = TOLD.computeIfAbsent(self.identity().id() + ">" + other.identity().id(), k -> new HashSet<>());
		String name = self.identity().name();
		for (Places.Place p : known) {
			long key = ((long) p.kind().ordinal() << 56) ^ ((long) (p.x() >> 3) << 28) ^ ((p.z() >> 3) & 0xFFFFFFFL);
			if (!alreadyTold.add(key)) {
				continue;
			}
			String what = WORTH_TELLING.get(p.kind());
			String where = whereFrom(here, p.x(), p.z());
			if (other.hearOfPlace(tick, p.kind(), p.x(), p.y(), p.z(), p.tick(), self.identity().id(), name,
					name + " told me " + what + " " + where + " of where we talked.")) {
				log.append(tick, EventType.TOLD, List.of(self.identity().id(), other.identity().id()),
						name + " told " + other.identity().name() + " " + what.replace("they ", name + " ") + " " + where + ".",
						List.of());
				return true;
			}
		}
		return false;
	}

	/** "about 60 blocks north-east": roughly, the way a person would say it. */
	static String whereFrom(BlockPos here, int x, int z) {
		int dx = x - here.getX(), dz = z - here.getZ();
		long distance = Math.max(10, Math.round(Math.sqrt((double) dx * dx + (double) dz * dz) / 10.0) * 10);
		// North is towards negative z.
		double degrees = Math.toDegrees(Math.atan2(-dz, dx));
		String[] ways = { "east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east" };
		String way = ways[(int) Math.floorMod(Math.round(degrees / 45.0), 8)];
		return "about " + distance + " blocks " + way;
	}

	private static void exchangeInformation(AgentMind self, AgentMind other, AgentEntity selfEntity,
			AgentEntity otherEntity, long tick, EventLog log) {
		// A death the other hasn't heard of comes first: it's passed on, not kept.
		if (Mourning.tellOfDeath(self, other, tick, log)) {
			self.needs().adjustSocial(0.05);
			other.needs().adjustSocial(0.02);
			return;
		}
		// Talk of home: how one's own house is built, which the other may take up.
		if (Imitation.describeHome(self, other, tick, selfEntity.getRandom().nextDouble(), log)) {
			self.relationships().with(other.identity().id()).recordConversation(tick, 0.03, 0.02);
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.05);
			return;
		}
		// What it has seen about saplings is worth passing on; to the listener it's hearsay until they see it too.
		if (self.recipeBook().knowsPractice(RecipeBook.REPLANTING)
				&& other.recipeBook().hearPractice(RecipeBook.REPLANTING,
						new RecipeBook.Learned("told", self.identity().name(), self.identity().id(), tick))) {
			// Told, traced to the teller's own memory of seeing it, when it still has one.
			Optional<MemoryEntry> seen = self.memories().retrieve(tick, 64).stream()
					.filter(m -> m.description().contains("Saplings grow into trees")).findFirst();
			if (seen.isPresent()) {
				other.receiveTold(tick, self.identity().id(), self.identity().name(), seen.get());
			} else {
				other.perceive(tick, self.identity().name() + " told me that saplings grow into trees if you plant them.", 0.5,
						Set.of(self.identity().id()));
			}
			log.append(tick, EventType.TOLD, List.of(self.identity().id(), other.identity().id()),
					self.identity().name() + " told " + other.identity().name() + " that saplings grow into trees if you plant them.",
					List.of());
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.05);
			return;
		}
		// Likewise that words can be left on a sign: hearsay to the listener until they read one or try it.
		if (self.recipeBook().knowsPractice(RecipeBook.WRITING)
				&& other.recipeBook().hearPractice(RecipeBook.WRITING,
						new RecipeBook.Learned("told", self.identity().name(), self.identity().id(), tick))) {
			Optional<MemoryEntry> seen = self.memories().retrieve(tick, 64).stream()
					.filter(m -> m.description().contains("can hold words") || m.description().contains("scratched words onto a sign"))
					.findFirst();
			if (seen.isPresent()) {
				other.receiveTold(tick, self.identity().id(), self.identity().name(), seen.get());
			} else {
				other.perceive(tick, self.identity().name() + " told me that words can be left on a sign for others to read.", 0.5,
						Set.of(self.identity().id()));
			}
			log.append(tick, EventType.TOLD, List.of(self.identity().id(), other.identity().id()),
					self.identity().name() + " told " + other.identity().name() + " that words can be left on a sign for others to read.",
					List.of());
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.05);
			return;
		}
		// Where things are, now and then rather than news: a place it has seen itself that the other doesn't know of.
		if (selfEntity.getRandom().nextBoolean() && tellOfPlace(self, other, selfEntity, tick, log)) {
			self.relationships().with(other.identity().id()).recordConversation(tick, 0.02, 0.01);
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.05);
			return;
		}
		// That digging in gets you through the night: hearsay to the listener until they try it and live.
		if (self.recipeBook().knowsPractice(RecipeBook.BURROWING)
				&& other.recipeBook().hearPractice(RecipeBook.BURROWING,
						new RecipeBook.Learned("told", self.identity().name(), self.identity().id(), tick))) {
			Optional<MemoryEntry> seen = self.memories().retrieve(tick, 64).stream()
					.filter(m -> m.description().contains("A hole closed over keeps you safe")).findFirst();
			if (seen.isPresent()) {
				other.receiveTold(tick, self.identity().id(), self.identity().name(), seen.get());
			} else {
				other.perceive(tick, self.identity().name() + " told me that a hole dug into the ground and closed over keeps"
						+ " you safe through the night.", 0.55, Set.of(self.identity().id()));
			}
			log.append(tick, EventType.TOLD, List.of(self.identity().id(), other.identity().id()),
					self.identity().name() + " told " + other.identity().name()
							+ " that digging into the ground and closing it over keeps you safe at night.", List.of());
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.05);
			return;
		}
		// That the dark kills: hearsay to the listener, weighing less than living through it.
		if (self.recipeBook().knowsPractice(RecipeBook.WARY_OF_THE_DARK)
				&& other.recipeBook().hearPractice(RecipeBook.WARY_OF_THE_DARK,
						new RecipeBook.Learned("told", self.identity().name(), self.identity().id(), tick))) {
			Optional<MemoryEntry> lived = self.memories().retrieve(tick, 64).stream()
					.filter(m -> m.description().contains("After dark, home is the place to be")
							|| m.description().contains("after dark, home is the place to be")).findFirst();
			if (lived.isPresent()) {
				other.receiveTold(tick, self.identity().id(), self.identity().name(), lived.get());
			} else {
				other.perceive(tick, self.identity().name() + " told me monsters nearly killed them out in the dark, and to be"
						+ " home before night.", 0.55, Set.of(self.identity().id()));
			}
			log.append(tick, EventType.TOLD, List.of(self.identity().id(), other.identity().id()),
					self.identity().name() + " told " + other.identity().name() + " to be home before dark: monsters nearly killed them.",
					List.of());
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.05);
			return;
		}
		// Only first-hand news (no retelling what someone else said), and nothing this listener has heard from us.
		String pair = self.identity().id() + ">" + other.identity().id();
		Set<Long> alreadyTold = TOLD.computeIfAbsent(pair, k -> new HashSet<>());
		MemoryEntry shared = null;
		for (MemoryEntry candidate : self.memories().retrieve(tick, 8)) {
			if (!(candidate.provenance() instanceof Provenance.Told) && isNews(candidate)
					&& !alreadyTold.contains((long) candidate.description().hashCode())
					&& !candidate.participants().contains(other.identity().id())) {
				shared = candidate;
				break;
			}
		}
		if (shared == null) {
			// Nothing new to tell: just pass the time.
			self.needs().adjustSocial(0.1);
			other.needs().adjustSocial(0.05);
			self.relationships().with(other.identity().id()).recordConversation(tick, 0.02, 0.0);
			logSmallTalk(selfEntity, self, other, tick, log);
			return;
		}
		if (TOLD.size() > 4096) {
			TOLD.clear();
		}
		alreadyTold.add((long) shared.description().hashCode());
		other.receiveTold(tick, self.identity().id(), self.identity().name(), shared);
		self.relationships().with(other.identity().id()).recordConversation(tick, 0.02, 0.02);

		log.append(tick, EventType.TOLD, List.of(self.identity().id(), other.identity().id()),
				self.identity().name() + " told " + other.identity().name() + ": " + shared.description(),
				List.of(Cause.memory(shared.id(), shared.description())));
		self.needs().adjustSocial(0.1);
		other.needs().adjustSocial(0.05);
	}
}
