package com.aicivilization.reasoning;

import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Goal;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Provenance;

import java.util.List;
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
				memories, goalDescriptions,
				mind.possessions().stream()
						.map(p -> p.quantity() + " " + p.itemId().replaceFirst("^[^:]*:", "").replace('_', ' '))
						.toList()
		);
	}
}
