package com.aicivilization.reasoning;

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
}
