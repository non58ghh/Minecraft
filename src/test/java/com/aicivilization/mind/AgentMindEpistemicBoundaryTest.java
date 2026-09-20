package com.aicivilization.mind;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turns the "no global/telepathic knowledge" architectural invariant into an
 * automated check instead of a code-review convention: nothing in
 * {@code com.aicivilization.mind} may accept a Minecraft world/server type,
 * the population registry, the event log, or another {@code AgentMind} as
 * an input. If this test ever fails, something was added that lets a mind
 * reach outside its own state — that's a bug, not a style nit.
 */
class AgentMindEpistemicBoundaryTest {

	private static final List<Class<?>> MIND_CLASSES = List.of(
			AgentMind.class, MemoryStream.class, MemoryEntry.class,
			Relationships.class, RelationshipData.class,
			Provenance.class, Provenance.Perceived.class, Provenance.Inferred.class, Provenance.Told.class,
			Belief.class, Goal.class, Possession.class,
			Identity.class, Personality.class, Needs.class, IntentType.class,
			DecisionTrace.class, DecisionTrace.ScoredIntent.class, Knowledge.class
	);

	private static final List<String> BANNED_EXACT = List.of(
			"com.aicivilization.population.PopulationRegistry",
			"com.aicivilization.population.Population",
			"com.aicivilization.events.EventLog",
			"com.aicivilization.mind.AgentMind"
	);

	@Test
	void noMindMethodOrConstructorAcceptsForbiddenTypes() {
		StringBuilder violations = new StringBuilder();
		for (Class<?> clazz : MIND_CLASSES) {
			for (Method method : clazz.getDeclaredMethods()) {
				checkParameters(clazz, method, method.getParameterTypes(), violations);
			}
			for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
				checkParameters(clazz, constructor, constructor.getParameterTypes(), violations);
			}
		}
		assertTrue(violations.isEmpty(), "Epistemic boundary violated:\n" + violations);
	}

	private void checkParameters(Class<?> owner, Executable member, Class<?>[] paramTypes, StringBuilder violations) {
		for (Class<?> paramType : paramTypes) {
			String name = paramType.getName();
			boolean isMinecraftType = name.startsWith("net.minecraft.");
			boolean isBanned = isMinecraftType || BANNED_EXACT.contains(name);
			if (isBanned) {
				violations.append(owner.getSimpleName()).append('.').append(member.getName())
						.append(" accepts forbidden type ").append(name).append('\n');
			}
		}
	}
}
