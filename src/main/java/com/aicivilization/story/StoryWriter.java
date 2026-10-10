package com.aicivilization.story;

import com.aicivilization.events.EventLog;
import com.aicivilization.events.SimEvent;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.population.PopulationRegistry;
import com.aicivilization.reasoning.ReasoningProvider;
import com.aicivilization.reasoning.StoryBrief;
import com.aicivilization.reasoning.StoryText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;

/**
 * Keeps the chronicle's stories up to date: takes new events into the
 * {@link StoryLog}, and when a story has settled, asks the LLM to write it
 * up from its record. At most one write is in flight, and they're at least
 * {@code intervalTicks} apart (0 turns writing off; stories are still
 * grouped and the page shows their records).
 */
public final class StoryWriter {

	/** How often new events are taken in. */
	public static final int CHECK_EVERY_TICKS = 100;
	/** A story with nothing new for this long is ready to write up (a minute of play). */
	static final long SETTLE_TICKS = 1200;
	/** Or this many events beyond its last write-up. */
	static final int BATCH = 4;
	/** Only stories from the last two game days are written up; older ones keep their record. */
	static final long MAX_AGE_TICKS = 48_000;
	/** A failed write-up waits this many intervals before that story is tried again. */
	static final int RETRY_INTERVALS = 5;
	private static final int MAX_RECORD_LINES = 40;
	private static final int MAX_LINE = 300;

	private final ReasoningProvider provider;
	private final long intervalTicks;
	private final BooleanSupplier simulationEnabled;
	private boolean pending;
	private long lastCallTick = Long.MIN_VALUE / 2;

	public StoryWriter(ReasoningProvider provider, long intervalTicks, BooleanSupplier simulationEnabled) {
		this.provider = provider;
		this.intervalTicks = Math.max(0, intervalTicks);
		this.simulationEnabled = simulationEnabled;
	}

	/** On the server thread. */
	public void tick(ServerLevel world, long tick, Executor mainThread) {
		StoryLog stories = StoryLog.get(world);
		StoryGrouper grouper = stories.grouper();
		EventLog events = EventLog.get(world);
		Map<UUID, String> names = new HashMap<>();
		Map<String, UUID> nameIndex = new HashMap<>();
		for (AgentMind mind : PopulationRegistry.get(world).population().allMinds()) {
			names.put(mind.identity().id(), mind.identity().name());
			// The living keep a name when the dead shared it.
			if (mind.isAlive() || !nameIndex.containsKey(mind.identity().name())) {
				nameIndex.put(mind.identity().name(), mind.identity().id());
			}
		}
		List<SimEvent> fresh = events.since(grouper.lastEventId());
		for (SimEvent e : fresh) {
			grouper.accept(e, nameIndex);
		}
		grouper.settle(tick);
		if (!fresh.isEmpty()) {
			stories.changed();
		}

		if (intervalTicks == 0 || pending || !simulationEnabled.getAsBoolean() || tick - lastCallTick < intervalTicks) {
			return;
		}
		Optional<Story> next = grouper.nextToWrite(tick, SETTLE_TICKS, BATCH, MAX_AGE_TICKS);
		if (next.isEmpty()) {
			return;
		}
		Story story = next.get();
		int covered = story.eventIds().size();
		StoryBrief brief = brief(story, events, names);
		if (brief.record().isEmpty()) {
			// Its events have all rolled out of the log: nothing left to write from.
			story.written("", "", "", covered);
			stories.changed();
			return;
		}
		pending = true;
		lastCallTick = tick;
		provider.narrate(brief)
				.exceptionally(ex -> Optional.empty())
				.thenAccept(result -> mainThread.execute(() -> {
					pending = false;
					if (result.isPresent()) {
						StoryText t = result.get();
						story.written(t.headline(), t.text(), t.stands(), covered);
					} else {
						story.retryAfter(tick + intervalTicks * RETRY_INTERVALS);
					}
					stories.changed();
				}));
	}

	static StoryBrief brief(Story story, EventLog events, Map<UUID, String> names) {
		List<String> record = new ArrayList<>();
		for (long id : story.eventIds()) {
			Optional<SimEvent> e = events.byId(id);
			if (e.isEmpty() || record.size() >= MAX_RECORD_LINES) {
				continue;
			}
			record.add(when(e.get().tick()) + " · " + clip(e.get().summary()));
			for (String line : e.get().transcript()) {
				if (record.size() < MAX_RECORD_LINES) {
					record.add("    " + clip(line));
				}
			}
		}
		List<String> people = story.people().stream().map(id -> names.getOrDefault(id, "someone")).toList();
		return new StoryBrief(people, record);
	}

	/** "Day 10, 10:26 pm", as the observer page shows game time. */
	static String when(long tick) {
		long day = tick / StoryGrouper.TICKS_PER_DAY;
		long tod = Math.floorMod(tick, StoryGrouper.TICKS_PER_DAY);
		long hours = (tod / 1000 + 6) % 24;
		long minutes = (tod % 1000) * 60 / 1000;
		String ampm = hours < 12 ? "am" : "pm";
		long h12 = hours % 12 == 0 ? 12 : hours % 12;
		return "Day " + day + ", " + h12 + ":" + (minutes < 10 ? "0" : "") + minutes + " " + ampm;
	}

	private static String clip(String s) {
		return s.length() <= MAX_LINE ? s : s.substring(0, MAX_LINE) + "…";
	}
}
