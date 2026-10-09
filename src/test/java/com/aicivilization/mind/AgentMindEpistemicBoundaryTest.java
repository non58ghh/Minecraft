package com.aicivilization.mind;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

	/**
	 * Every class in the mind package, found on disk rather than listed by
	 * hand, so a new class (a recipe book, a plan) can't slip past the check.
	 */
	private static List<Class<?>> mindClasses() throws Exception {
		String pkg = "com.aicivilization.mind";
		// Where AgentMind itself was loaded from (the main classes, not the tests' copy of the package).
		Path dir = Path.of(AgentMind.class.getProtectionDomain().getCodeSource().getLocation().toURI())
				.resolve(pkg.replace('.', '/'));
		List<Class<?>> classes = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				String name = file.getFileName().toString();
				if (name.endsWith(".class")) {
					classes.add(Class.forName(pkg + "." + name.substring(0, name.length() - ".class".length()), false,
							AgentMind.class.getClassLoader()));
				}
			}
		}
		return classes;
	}

	private static final List<String> BANNED_EXACT = List.of(
			"com.aicivilization.population.PopulationRegistry",
			"com.aicivilization.population.Population",
			"com.aicivilization.events.EventLog",
			"com.aicivilization.mind.AgentMind"
	);

	@Test
	void noMindMethodOrConstructorAcceptsForbiddenTypes() throws Exception {
		StringBuilder violations = new StringBuilder();
		List<Class<?>> classes = mindClasses();
		assertTrue(classes.size() > 20, "found only " + classes.size() + " mind classes");
		for (Class<?> clazz : classes) {
			for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
				String name = field.getType().getName();
				if (name.startsWith("net.minecraft.") || name.startsWith("com.aicivilization.population.")
						|| name.equals("com.aicivilization.events.EventLog")) {
					violations.append(clazz.getSimpleName()).append('.').append(field.getName())
							.append(" holds forbidden type ").append(name).append('\n');
				}
			}
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
