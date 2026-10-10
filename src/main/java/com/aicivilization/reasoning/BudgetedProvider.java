package com.aicivilization.reasoning;

import com.aicivilization.mind.Design;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A provider that spends from a {@link CallBudget}. Conversations and
 * chronicle write-ups are extras; a home design counts as an agent's own
 * thinking. Out of budget, each falls back as it does without an LLM: no
 * transcript, the story waits, a generated design. An agent's reflection
 * ({@link #reason}) is checked by {@link ReasoningScheduler} before it
 * decides to think at all, so a pass is never begun and then dropped.
 */
public final class BudgetedProvider implements ReasoningProvider {

	private final ReasoningProvider inner;
	private final CallBudget budget;

	public BudgetedProvider(ReasoningProvider inner, CallBudget budget) {
		this.inner = inner;
		this.budget = budget;
	}

	public CallBudget budget() {
		return budget;
	}

	@Override
	public CompletableFuture<ReasoningResult> reason(AgentContext context) {
		return inner.reason(context);
	}

	@Override
	public CompletableFuture<Optional<Dialogue>> converse(DialogueBrief brief) {
		return budget.take(true) ? inner.converse(brief) : CompletableFuture.completedFuture(Optional.empty());
	}

	/** Someone is waiting on an answer: it counts as the agent's own, not an extra. */
	@Override
	public CompletableFuture<Optional<ChatReply>> reply(ChatBrief brief) {
		return budget.take(false) ? inner.reply(brief) : CompletableFuture.completedFuture(Optional.empty());
	}

	@Override
	public CompletableFuture<Optional<StoryText>> narrate(StoryBrief brief) {
		return budget.take(true) ? inner.narrate(brief) : CompletableFuture.completedFuture(Optional.empty());
	}

	@Override
	public CompletableFuture<Optional<Design>> design(DesignBrief brief) {
		return budget.take(false) ? inner.design(brief) : ReasoningProvider.super.design(brief);
	}
}
