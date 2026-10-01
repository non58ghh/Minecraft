package com.aicivilization.command;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.Cause;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.SimEvent;
import com.aicivilization.events.WorldChronicle;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.DecisionTrace;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.population.PopulationRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * {@code /civ ...} — observation and debugging commands. None of these
 * exist for agents to use on themselves; they're a window for a human to
 * look into the simulation from outside.
 */
public final class CivCommands {

	private CivCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("civ")
				.then(literal("spawn")
						.then(argument("count", IntegerArgumentType.integer(1, 50))
								.executes(ctx -> spawn(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
				.then(literal("inspect")
						.then(argument("name", StringArgumentType.word())
								.executes(ctx -> inspect(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
				.then(literal("history")
						.executes(ctx -> history(ctx.getSource())))
				.then(literal("why")
						.then(argument("name", StringArgumentType.word())
								.executes(ctx -> why(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))));
	}

	private static int spawn(CommandSourceStack source, int count) {
		ServerLevel world = source.getLevel();
		Vec3 origin = source.getPosition();
		for (int i = 0; i < count; i++) {
			AgentEntity entity = new AgentEntity(AICivilizationMod.AGENT_ENTITY_TYPE, world);
			double angle = i * (Math.PI * 2 / Math.max(1, count));
			double radius = 2.0 + (i % 3);
			entity.setPos(origin.x + Math.cos(angle) * radius, origin.y, origin.z + Math.sin(angle) * radius);
			entity.setYRot(0.0f);
			entity.setXRot(0.0f);
			world.addFreshEntity(entity);
			entity.mind(); // force creation now so /civ inspect can find it immediately.
		}
		source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Spawned " + count + " agents."), true);
		return count;
	}

	private static int inspect(CommandSourceStack source, String name) {
		ServerLevel world = source.getLevel();
		Optional<AgentMind> found = findByName(world, name);
		if (found.isEmpty()) {
			source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("No agent named " + name + " found."), false);
			return 0;
		}
		AgentMind mind = found.get();
		long tick = world.getGameTime();
		StringBuilder sb = new StringBuilder();
		sb.append(mind.identity().name()).append(" (age ").append(mind.identity().ageInTicks(tick) / 24000L).append(" days)\n");
		sb.append(String.format("Personality: curiosity=%.2f risk=%.2f sociability=%.2f ambition=%.2f%n",
				mind.personality().curiosity(), mind.personality().risk(),
				mind.personality().sociability(), mind.personality().ambition()));
		sb.append(String.format("Needs: food=%.2f safety=%.2f social=%.2f belonging=%.2f%n",
				mind.needs().food(), mind.needs().safety(), mind.needs().social(), mind.needs().belonging()));
		sb.append("Goals: ");
		mind.goals().stream().filter(g -> g.active()).forEach(g -> sb.append(g.description()).append("; "));
		sb.append('\n');
		sb.append("Recent memories:\n");
		for (MemoryEntry entry : mind.memories().retrieve(tick, 6)) {
			sb.append("  - ").append(entry.description()).append(" [").append(describeProvenance(entry)).append("]\n");
		}
		sb.append("Relationships: ").append(mind.relationships().asMap().size()).append(" known agents\n");

		String output = sb.toString();
		source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(output), false);
		return 1;
	}

	private static int history(CommandSourceStack source) {
		EventLog log = EventLog.get(source.getLevel());
		List<String> lines = WorldChronicle.render(log);
		if (lines.isEmpty()) {
			source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("The chronicle is empty so far."), false);
			return 0;
		}
		StringBuilder sb = new StringBuilder("World Chronicle:\n");
		for (String line : lines) {
			sb.append(line).append('\n');
		}
		String output = sb.toString();
		source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(output), false);
		return lines.size();
	}

	private static int why(CommandSourceStack source, String name) {
		ServerLevel world = source.getLevel();
		Optional<AgentMind> found = findByName(world, name);
		if (found.isEmpty()) {
			source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("No agent named " + name + " found."), false);
			return 0;
		}
		AgentMind mind = found.get();
		List<DecisionTrace> decisions = mind.recentDecisions();
		if (decisions.isEmpty()) {
			source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(mind.identity().name() + " hasn't decided anything yet."), false);
			return 0;
		}
		DecisionTrace trace = decisions.get(decisions.size() - 1);
		StringBuilder sb = new StringBuilder();
		sb.append(mind.identity().name()).append(" chose to ").append(trace.chosen()).append(" at tick ").append(trace.tick()).append(".\n");
		sb.append("Candidates considered:\n");
		for (DecisionTrace.ScoredIntent candidate : trace.candidates()) {
			sb.append(String.format("  %s: score=%.3f %s%n", candidate.intent(), candidate.score(), candidate.factors()));
		}
		sb.append("Because of:\n");
		for (Cause cause : trace.causes()) {
			sb.append("  - ").append(cause.sourceType()).append(' ').append(cause.sourceId());
			if (cause.detail() != null && !cause.detail().isBlank()) {
				sb.append(" (").append(cause.detail()).append(')');
			}
			sb.append('\n');
		}
		EventLog log = EventLog.get(world);
		List<SimEvent> recentEvents = log.forAgent(mind.identity().id(), 5);
		sb.append("Recent logged events:\n");
		for (SimEvent event : recentEvents) {
			sb.append("  - [").append(event.type()).append("] ").append(event.summary()).append('\n');
		}
		String output = sb.toString();
		source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(output), false);
		return 1;
	}

	private static Optional<AgentMind> findByName(ServerLevel world, String name) {
		return PopulationRegistry.get(world).population().allMinds().stream()
				.filter(m -> m.identity().name().equalsIgnoreCase(name))
				.findFirst();
	}

	private static String describeProvenance(MemoryEntry entry) {
		return switch (entry.provenance()) {
			case com.aicivilization.mind.Provenance.Perceived p -> "perceived";
			case com.aicivilization.mind.Provenance.Inferred i -> "inferred";
			case com.aicivilization.mind.Provenance.Told t -> "told";
		};
	}
}
