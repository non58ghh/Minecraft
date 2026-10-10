package com.aicivilization.reasoning;

import com.aicivilization.mind.Design;
import com.aicivilization.mind.DesignGenerator;
import com.aicivilization.mind.Personality;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The "deep system": pluggable higher-level reasoning for one agent, given
 * only that agent's own context (see {@link AgentContext}). Invoked off the
 * hot path (throttled + novelty-gated by the caller), so implementations
 * are free to be slow (e.g. a network call) — {@link #reason} returns a
 * future rather than blocking.
 */
public interface ReasoningProvider {
	CompletableFuture<ReasoningResult> reason(AgentContext context);

	/**
	 * The agent's own design for a home, asked for once per agent. Empty if
	 * none could be made; the default draws one procedurally, no LLM involved.
	 */
	/** Two agents' conversation, written out. Empty when there's no LLM: they talk without a transcript. */
	default CompletableFuture<Optional<Dialogue>> converse(DialogueBrief brief) {
		return CompletableFuture.completedFuture(Optional.empty());
	}

	/** A chronicle story written up from its record. Empty when there's no LLM: the page shows the record itself. */
	default CompletableFuture<Optional<StoryText>> narrate(StoryBrief brief) {
		return CompletableFuture.completedFuture(Optional.empty());
	}

	default CompletableFuture<Optional<Design>> design(DesignBrief brief) {
		return CompletableFuture.completedFuture(Optional.of(DesignGenerator.generate(brief.agentName(),
				new Personality(brief.curiosity(), brief.risk(), brief.sociability(), brief.ambition()), brief.seed())));
	}
}
