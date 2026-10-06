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
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
				.then(literal("off")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(ctx -> setSimulation(ctx.getSource(), false)))
				.then(literal("on")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(ctx -> setSimulation(ctx.getSource(), true)))
				.then(literal("status")
						.executes(ctx -> status(ctx.getSource())))
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

	private static int setSimulation(CommandSourceStack source, boolean enabled) {
		boolean changed = AICivilizationMod.isSimulationEnabled() != enabled;
		AICivilizationMod.setSimulationEnabled(enabled);
		String message = enabled
				? (changed ? "AI Civilization is ON: agents are acting and thinking again." : "AI Civilization was already on.")
				: (changed ? "AI Civilization is OFF: agents are frozen and no AI calls are made. /civ on to resume."
						: "AI Civilization was already off.");
		source.sendSuccess(() -> Component.literal(message), true);
		return 1;
	}

	private static int status(CommandSourceStack source) {
		boolean on = AICivilizationMod.isSimulationEnabled();
		String hours = " Active hours: " + AICivilizationMod.activeHoursDescription()
				+ (on && !AICivilizationMod.isWithinActiveHours() ? " (agents are resting until then)." : ".");
		source.sendSuccess(() -> Component.literal("AI Civilization is "
				+ (on ? "ON." : "OFF. An operator can run /civ on to resume.") + hours), false);
		return AICivilizationMod.isSimulationRunning() ? 1 : 0;
	}

	private static int spawn(CommandSourceStack source, int count) {
		ServerLevel world = source.getLevel();
		Vec3 origin = source.getPosition();
		for (int i = 0; i < count; i++) {
			AgentEntity entity = new AgentEntity(AICivilizationMod.AGENT_ENTITY_TYPE, world);
			double angle = i * (Math.PI * 2 / Math.max(1, count));
			double radius = 2.0 + (i % 3);
			double x = origin.x + Math.cos(angle) * radius;
			double z = origin.z + Math.sin(angle) * radius;
			// Ring spots can be over a drop when the caller stands on a ledge or is
			// flying; fall back to the caller's own position rather than spawn in midair.
			if (!placeOnGround(world, entity, x, origin.y, z)) {
				entity.setPos(origin.x, origin.y, origin.z);
			}
			entity.setYRot(0.0f);
			entity.setXRot(0.0f);
			world.addFreshEntity(entity);
			entity.mind(); // force creation now so /civ inspect can find it immediately.
		}
		source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Spawned " + count + " agents."), true);
		return count;
	}

	/**
	 * Moves {@code entity} to the first spot within a few blocks of
	 * {@code y} at ({@code x}, {@code z}) that has a solid block underneath
	 * and room to stand. Returns false (leaving the position unspecified)
	 * if there is none.
	 */
	private static boolean placeOnGround(ServerLevel world, AgentEntity entity, double x, double y, double z) {
		BlockPos start = BlockPos.containing(x, y, z);
		for (int dy = 2; dy >= -4; dy--) {
			BlockPos feet = start.above(dy);
			BlockPos below = feet.below();
			if (!world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) {
				continue;
			}
			entity.setPos(x, feet.getY(), z);
			if (world.noCollision(entity)) {
				return true;
			}
		}
		return false;
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
		sb.append("Carrying: ");
		if (mind.possessions().isEmpty()) {
			sb.append("nothing");
		}
		for (var item : mind.possessions()) {
			sb.append(item.quantity()).append(" ").append(com.aicivilization.action.ItemKinds.displayName(item.itemId())).append("; ");
		}
		sb.append('\n');
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
