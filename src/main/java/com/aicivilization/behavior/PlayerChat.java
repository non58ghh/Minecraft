package com.aicivilization.behavior;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.reasoning.ChatBrief;
import com.aicivilization.reasoning.ChatReply;
import com.aicivilization.reasoning.ReasoningProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Players can talk to agents. Saying something in chat near an agent (or
 * naming one nearby) gets an answer from that agent, in its own words,
 * from what it knows: its situation, what's been on its mind, and what has
 * passed between them before. The agent hears only what was said within
 * earshot and remembers it like anything else that happened to it, so
 * the player can come up when it thinks things over or talks to others.
 * Without a model to write the answer (or with no calls left this hour),
 * the agent hears and remembers but doesn't answer.
 */
public final class PlayerChat {

	/** Said within this of an agent, it's heard. */
	static final double EARSHOT = 16;
	/** The answer carries this far, to anyone in range. */
	static final double ANSWER_CARRIES = 48;
	/** One answer at a time from each agent, at most this often. */
	static final long REPLY_GAP_TICKS = 60;
	private static final int EARLIER_LINES = 6;

	private static ReasoningProvider writer;
	private static final Map<UUID, Long> lastReply = new HashMap<>();
	private static final Set<UUID> answering = new java.util.HashSet<>();

	private PlayerChat() {
	}

	public static void useWriter(ReasoningProvider provider) {
		writer = provider;
	}

	/** Who a line is meant for: an agent in earshot named in it, else the nearest in earshot. */
	static <T> Optional<T> addressee(String said, List<T> inEarshot, java.util.function.Function<T, String> nameOf) {
		String lower = said.toLowerCase(Locale.ROOT);
		for (T t : inEarshot) {
			String name = nameOf.apply(t);
			if (name != null && lower.matches(".*\\b" + java.util.regex.Pattern.quote(name.toLowerCase(Locale.ROOT)) + "\\b.*")) {
				return Optional.of(t);
			}
		}
		return inEarshot.stream().findFirst();
	}

	/** Fabric's chat event, on the server thread. */
	public static void onChat(PlayerChatMessage message, ServerPlayer player, Object unusedType) {
		if (!AICivilizationMod.isSimulationRunning() || !(player.level() instanceof ServerLevel world)) {
			return;
		}
		String said = message.signedContent().strip();
		if (said.isEmpty() || said.startsWith("/")) {
			return;
		}
		List<AgentEntity> near = new ArrayList<>(world.getEntities(AICivilizationMod.AGENT_ENTITY_TYPE,
				player.getBoundingBox().inflate(EARSHOT), e -> e.isAlive() && e.mind() != null && e.mind().isAlive()));
		near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
		Optional<AgentEntity> to = addressee(said, near, e -> e.mind().identity().name());
		if (to.isEmpty()) {
			return;
		}
		AgentEntity agent = to.get();
		AgentMind mind = agent.mind();
		long tick = world.getGameTime();
		UUID playerId = player.getUUID();
		String playerName = player.getName().getString();
		List<String> earlier = mind.memories().all().stream()
				.filter(m -> m.participants().contains(playerId))
				.sorted(Comparator.comparingLong(MemoryEntry::tick))
				.map(MemoryEntry::description)
				.toList();
		earlier = earlier.subList(Math.max(0, earlier.size() - EARLIER_LINES), earlier.size());
		mind.perceive(tick, playerName + " (a player) said to me: \"" + said + "\"", 0.55, Set.of(playerId));
		mind.relationships().with(playerId).recordConversation(tick, 0.01, 0.0);
		mind.needs().adjustSocial(0.05);
		agent.getLookControl().setLookAt(player);
		Long last = lastReply.get(mind.identity().id());
		if (writer == null || answering.contains(mind.identity().id()) || last != null && tick - last < REPLY_GAP_TICKS) {
			return;
		}
		lastReply.put(mind.identity().id(), tick);
		answering.add(mind.identity().id());
		ChatBrief brief = new ChatBrief(ConversationBehavior.speaker(mind, playerId, playerName, tick), playerName, said,
				earlier, ConversationBehavior.timeOfDay(world.getOverworldClockTime() % 24000L));
		var server = world.getServer();
		writer.reply(brief)
				.exceptionally(ex -> Optional.empty())
				.thenAccept(reply -> server.execute(() -> {
					answering.remove(mind.identity().id());
					reply.ifPresent(r -> answer(world, agent, mind, playerId, playerName, said, r, tick));
				}));
	}

	private static void answer(ServerLevel world, AgentEntity agent, AgentMind mind, UUID playerId, String playerName,
			String said, ChatReply reply, long tick) {
		if (!agent.isAlive() || !mind.isAlive()) {
			return;
		}
		String name = mind.identity().name();
		Component line = Component.literal("<" + name + "> " + reply.reply());
		for (ServerPlayer p : world.players()) {
			if (p.distanceToSqr(agent) <= ANSWER_CARRIES * ANSWER_CARRIES) {
				p.sendSystemMessage(line);
			}
		}
		if (!reply.remembers().isEmpty()) {
			mind.inferMemory(tick, reply.remembers(), 0.5, Set.of(playerId), -1);
		} else {
			mind.perceive(tick, "I told " + playerName + ": \"" + reply.reply() + "\"", 0.4, Set.of(playerId));
		}
		EventLog.get(world).append(tick, EventType.CONVERSATION, List.of(mind.identity().id()),
				name + " talked with " + playerName + ", a player.", List.of(),
				List.of(playerName + ": " + said, name + ": " + reply.reply()));
	}
}
