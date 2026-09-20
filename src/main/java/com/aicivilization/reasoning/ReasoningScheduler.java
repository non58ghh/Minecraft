package com.aicivilization.reasoning;

import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Goal;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Provenance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Decides <em>when</em> to invoke the "deep system" for a given agent
 * (throttled, novelty-gated — a real LLM call is not cheap) and applies the
 * result back once it resolves. This is the per-agent cost-control layer
 * the design calls for: routine reflection every {@code intervalTicks}, or
 * sooner if the agent just entered a need crisis.
 */
public final class ReasoningScheduler {

	private static final long CRISIS_COOLDOWN_TICKS = 200;

	private final ReasoningProvider provider;
	private final long intervalTicks;
	private final Map<UUID, Long> lastInvokedTick = new HashMap<>();

	public ReasoningScheduler(ReasoningProvider provider, long intervalTicks) {
		this.provider = provider;
		this.intervalTicks = intervalTicks;
	}

	public void maybeInvoke(AgentMind mind, long tick, EventLog log, Executor mainThreadExecutor) {
		UUID id = mind.identity().id();
		long last = lastInvokedTick.getOrDefault(id, Long.MIN_VALUE / 2);
		boolean crisis = mind.needs().hasCrisis();
		boolean intervalElapsed = tick - last >= intervalTicks;
		boolean crisisElapsed = crisis && tick - last >= CRISIS_COOLDOWN_TICKS;

		if (!intervalElapsed && !crisisElapsed) {
			return;
		}
		lastInvokedTick.put(id, tick);

		AgentContext context = buildContext(mind, tick);
		String trigger = crisis ? "a need crisis" : "routine reflection";
		log.append(tick, EventType.REASONING_INVOKED, List.of(id),
				mind.identity().name() + " stopped to think, prompted by " + trigger + ".", List.of());

		provider.reason(context)
				.thenAccept(result -> mainThreadExecutor.execute(() -> apply(mind, result, tick, log)));
	}

	private void apply(AgentMind mind, ReasoningResult result, long tick, EventLog log) {
		result.goalDescription().ifPresent(description -> {
			boolean alreadyPursuing = mind.goals().stream()
					.anyMatch(g -> g.active() && g.description().equals(description));
			if (!alreadyPursuing) {
				mind.addGoal(tick, description, result.goalPriority(), result.relatedIntent().orElse(null));
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

	private static String summarize(ReasoningResult result) {
		if (result.goalDescription().isPresent()) {
			return "wants to " + result.goalDescription().get();
		}
		if (result.beliefStatement().isPresent()) {
			return result.beliefStatement().get();
		}
		return "nothing in particular this time";
	}

	private static AgentContext buildContext(AgentMind mind, long tick) {
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
				memories, goalDescriptions
		);
	}
}
