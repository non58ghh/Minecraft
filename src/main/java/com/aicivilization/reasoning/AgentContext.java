package com.aicivilization.reasoning;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a {@link ReasoningProvider} is given to reason about one
 * agent's situation — built <em>only</em> from that agent's own
 * {@code AgentMind} (its needs, personality, memories, goals, the people it
 * knows) and what it can perceive where it stands ({@link Situation}).
 * Never from global world state, so the epistemic rule holds even when an
 * LLM is doing the thinking: it can only "know" what's in this context.
 *
 * <p>The prompt is written in plain words, section by section (the world
 * now, who you are, how you're doing, where you are, people you know, what's
 * been happening, what you're working on, what you can do), and asks for
 * the activity first and the goal as what that activity will really do: the
 * game acts on the activity, not on names, places or times in the goal.
 */
public record AgentContext(
		String agentName,
		long tick,
		double curiosity,
		double risk,
		double sociability,
		double ambition,
		double food,
		double safety,
		double social,
		double belonging,
		/** Notable recent memories, oldest first, each already labelled with its day; routine chores folded. */
		List<String> recentMemories,
		List<String> activeGoalDescriptions,
		List<String> carrying,
		List<String> recentBeliefs,
		String home,
		/** What it knows how to make (short item names). */
		List<String> canMake,
		/** Day, time of day, where it is and who's in sight; {@link Situation#unknown()} if not known. */
		Situation situation,
		/** Meals' worth of food carried (a loaf of bread is about one). */
		int mealsCarried,
		/** People it knows, closest first, each one line ("Ilse: someone you like. Last seen day 5."). */
		List<String> people,
		/** Its active goals with how long and how far ("Find Ilse. Since day 5; not done yet."). */
		List<String> goalLines,
		/** Things that went wrong lately ("Gave up on making oak logs: couldn't get 20."). */
		List<String> setbacks
) {

	/** Day, daylight and surroundings as the agent perceives them where it stands. */
	public record Situation(long day, long timeOfDay, String place, List<String> inSight, int population) {
		public Situation {
			inSight = List.copyOf(inSight);
		}

		public static Situation unknown() {
			return new Situation(-1, -1, "", List.of(), 0);
		}
	}

	/** Offered only to those who know of writing (see {@code canMake}): nobody else knows signs can hold words. */
	static final String WRITE_SIGN_ACTIVITY = "WRITE_SIGN: put up a sign where you stand, for people passing later: where the "
			+ "trees are when there are none here, or a warning where monsters came at night. Takes two planks or a log; "
			+ "only when there's something like that worth writing here.";
	/** What {@code canMake} says when it knows, or has heard, that signs hold words. */
	static final String SIGNS = "signs";

	/** The activities it can choose, as they really play out. */
	static final List<String> ACTIVITIES = List.of(
			"FORAGE_FOOD: look for food: hunt animals, pick ripe crops and berries.",
			"FARM: plant, tend and harvest a wheat field; bake bread from the wheat.",
			"GATHER_MATERIALS: chop trees, dig stone or ore. With no trees in sight, you go to trees you remember or look further out.",
			"BUILD_SHELTER: build your home from the wood you carry (needs some wood and a clear spot).",
			"GO_HOME: walk back to your home, if you have one.",
			"SOCIALIZE: go to the nearest person, or to where you last saw someone you like, and talk: share news, trade, ask for help, make plans.",
			"EXPLORE: walk somewhere you haven't been.",
			"SEEK_SAFETY: run from danger you can see, such as a monster. It doesn't find shelter or build anything.",
			"FIGHT: stand and fight a monster that's close, to defend yourself or someone else. A sword helps; it hurts back.",
			"REST: rest where you are; at home it restores you far more.",
			WRITE_SIGN_ACTIVITY,
			"IDLE: nothing in particular.");

	/** A compact natural-language description an LLM provider can reason over. */
	public String toPromptSummary() {
		StringBuilder sb = new StringBuilder();
		String people = situation.population() > 1 ? "one of " + situation.population() + " people" : "one of the people";
		sb.append("You are ").append(agentName).append(", ").append(people)
				.append(" in a young settlement in a Minecraft world. Nobody has a role or a job here. Think as ")
				.append(agentName).append(" would, from ").append(agentName)
				.append("'s own needs, character and experience, and decide what ").append(agentName)
				.append(" will do next.\n");

		if (situation.day() >= 0) {
			section(sb, "THE WORLD RIGHT NOW");
			sb.append(timeLine(situation.day(), situation.timeOfDay())).append('\n');
		}

		section(sb, "WHO YOU ARE");
		sb.append(personalityWords(curiosity, risk, sociability, ambition)).append('\n');

		section(sb, "HOW YOU'RE DOING");
		for (String line : needLines(food, safety, social, belonging, mealsCarried, home == null || home.isEmpty() || home.startsWith("none yet"))) {
			sb.append("- ").append(line).append('\n');
		}
		sb.append("- Carrying: ").append(carrying.isEmpty() ? "nothing" : String.join(", ", carrying)).append('\n');

		if (!situation.place().isEmpty() || situation.day() >= 0) {
			section(sb, "WHERE YOU ARE");
			sb.append(situation.place().isEmpty() ? "" : situation.place() + " ");
			sb.append(situation.inSight().isEmpty() ? "Nobody is in sight." : "In sight: " + String.join(", ", situation.inSight()) + ".");
			sb.append('\n');
		}

		section(sb, "PEOPLE YOU KNOW");
		lines(sb, people(), "Nobody yet.");

		section(sb, "WHAT'S BEEN HAPPENING (oldest first)");
		lines(sb, recentMemories, "Nothing much yet.");

		section(sb, "WHAT YOU'RE WORKING ON");
		lines(sb, goalLines, "Nothing in particular.");
		sb.append("- Your home: ").append(home == null || home.isEmpty() ? "none yet." : home).append('\n');
		sb.append("Setbacks lately: ").append(setbacks.isEmpty() ? "none." : String.join(" ", setbacks)).append('\n');

		if (!recentBeliefs.isEmpty()) {
			section(sb, "WHAT YOU BELIEVE");
			lines(sb, recentBeliefs, "");
		}

		section(sb, "WHAT YOU CAN DO");
		sb.append("Your goal is a note to yourself. What you actually do is the activity you pick: it doesn't follow names, "
				+ "places or times written in the goal.\n");
		boolean writes = canMake.stream().anyMatch(c -> c.startsWith(SIGNS));
		for (String activity : ACTIVITIES) {
			if (activity.equals(WRITE_SIGN_ACTIVITY) && !writes) {
				continue;
			}
			sb.append("- ").append(activity).append('\n');
		}
		sb.append("To make or get one particular thing, name it in target and you'll work out the steps. You know how to make "
				+ "or get: ").append(canMake.isEmpty() ? "nothing yet" : String.join(", ", canMake)).append(".\n");
		sb.append("When people meet they can share news, trade, ask each other for help and make plans together. ")
				.append("A plan you've already agreed with someone stands: you don't need to find them again to confirm it; ")
				.append("get on with your part.\n");

		section(sb, "REPLY");
		sb.append("First choose the one activity ").append(agentName).append(" will actually do next. Then write the goal as what ")
				.append(agentName).append(" will do with that activity, as it will really play out.\n");
		sb.append("Only a single-line JSON object, no other text:\n");
		sb.append("{\"relatedIntent\": \"<one activity above>\", \"goal\": \"<what ").append(agentName)
				.append(" will do, 12 words or fewer>\", \"priority\": <0.3 a passing wish, 0.6 important, 0.9 what matters most now>, "
						+ "\"belief\": \"<something newly believed, 12 words or fewer, or empty>\", \"beliefConfidence\": <0 to 1>, "
						+ "\"target\": {\"item\": \"<Minecraft item id like iron_pickaxe, or empty>\", \"count\": 1}}\n");
		sb.append("Don't repeat a belief you already hold.\n");
		return sb.toString();
	}

	private static void section(StringBuilder sb, String title) {
		sb.append('\n').append(title).append('\n');
	}

	private static void lines(StringBuilder sb, List<String> lines, String none) {
		if (lines.isEmpty()) {
			if (!none.isEmpty()) {
				sb.append(none).append('\n');
			}
			return;
		}
		for (String line : lines) {
			sb.append("- ").append(line).append('\n');
		}
	}

	/** "Day 6, early evening. Night falls in about 3 minutes, and monsters come out in the dark." At night, that dawn ends the danger. */
	static String timeLine(long day, long timeOfDay) {
		long tod = Math.floorMod(timeOfDay, 24000L);
		String part;
		if (tod < 1000) {
			part = "dawn";
		} else if (tod < 5000) {
			part = "morning";
		} else if (tod < 7000) {
			part = "midday";
		} else if (tod < 10000) {
			part = "afternoon";
		} else if (tod < 12000) {
			part = "early evening";
		} else if (tod < 13000) {
			part = "dusk";
		} else if (tod < 23000) {
			part = "night";
		} else {
			part = "just before dawn";
		}
		StringBuilder s = new StringBuilder("Day ").append(day).append(", ").append(part).append(". ");
		if (tod < 13000) {
			long minutes = Math.max(1, Math.round((13000 - tod) / 1200.0));
			s.append(tod >= 10000 ? "Night falls in about " + minutes + (minutes == 1 ? " minute" : " minutes")
					+ ", and monsters come out in the dark." : "It's daylight; night is a while off.");
		} else {
			long minutes = Math.max(1, Math.round((24000 - tod) / 1200.0));
			// Said outright which way round it is: agents read "dawn in a minute" as the danger arriving.
			s.append("It's dark, and monsters are out and will attack you. Dawn comes in about ").append(minutes)
					.append(minutes == 1 ? " minute" : " minutes").append("; at dawn the danger ends, as daylight burns zombies and skeletons.");
		}
		return s.toString();
	}

	/** "Warm and outgoing; cautious; middling curiosity; not very ambitious." */
	static String personalityWords(double curiosity, double risk, double sociability, double ambition) {
		List<String> words = new ArrayList<>();
		words.add(sociability > 0.65 ? "warm and outgoing" : sociability < 0.35 ? "reserved" : "friendly enough");
		words.add(risk > 0.65 ? "bold" : risk < 0.35 ? "cautious" : "steady");
		words.add(curiosity > 0.65 ? "very curious" : curiosity < 0.35 ? "not very curious" : "middling curiosity");
		words.add(ambition > 0.65 ? "driven" : ambition < 0.35 ? "not very ambitious" : "fairly ambitious");
		String joined = String.join("; ", words);
		return Character.toUpperCase(joined.charAt(0)) + joined.substring(1) + ".";
	}

	/** Each need in words, with what bears on it (food carried, no home). */
	static List<String> needLines(double food, double safety, double social, double belonging, int meals, boolean homeless) {
		List<String> out = new ArrayList<>();
		String hunger = food < 0.3 ? "very hungry" : food < 0.55 ? "hungry" : food < 0.8 ? "fed" : "well fed";
		String carried = meals <= 0 ? ", and you carry no food." : ", but you're carrying about " + meals
				+ (meals == 1 ? " meal's worth." : " meals' worth.");
		if (food >= 0.55) {
			carried = meals <= 0 ? "; you carry no food." : "; you carry about " + meals + (meals == 1 ? " meal." : " meals.");
		}
		out.add("Food: " + hunger + carried);
		out.add("Safety: " + (safety < 0.3 ? "you feel in danger" : safety < 0.6 ? "you feel exposed" : "safe enough")
				+ (homeless ? ". You have no home." : "."));
		out.add("Company: " + (social < 0.3 ? "very lonely." : social < 0.6 ? "a bit lonely." : "content with the company you've had."));
		out.add("Belonging: " + (belonging < 0.3 ? "rootless." : belonging < 0.6 ? "unsettled." : "you feel you belong here."));
		return out;
	}

	/** "Today", "Yesterday", or "Day 4", for the day a tick falls on, seen from {@code nowTick}. */
	public static String dayLabel(long tick, long nowTick) {
		long day = Math.floorDiv(tick, 24000L);
		long today = Math.floorDiv(nowTick, 24000L);
		return day == today ? "Today" : day == today - 1 ? "Yesterday" : "Day " + day;
	}
}
