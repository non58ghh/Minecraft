package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Possession;
import com.aicivilization.mind.RecipeBook;
import com.aicivilization.world.Library;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Signs: reading them and putting them up. Nobody starts out knowing
 * that marks on a sign hold words ({@link RecipeBook#WRITING}). An agent
 * learns it by reading a sign someone else put up (a player's included),
 * or by trying it, which only the curious do unprompted, and those who've
 * heard of it. What it writes is what it knows that people passing here
 * would want to: where the trees are when there are none in sight, that
 * monsters came at night. A reader gets the words and
 * nothing else (see {@link SignWords}), remembered as read
 * ({@code Provenance.Read}).
 */
public final class Writing {

	/** How far around it reads signs. */
	public static final int READ_DISTANCE = 6;
	/** A sign already saying the same sort of thing this close means there's no need for another. */
	private static final int SAME_NEWS_DISTANCE = 24;
	/** Trees nearer than this need no sign. */
	private static final int WOODS_WORTH_POINTING_TO = 24;
	/** It posts word of the woods where people come and go: within this of its home. */
	private static final int NEAR_HOME = 16;
	/** An attack this long ago, this close, is still worth warning about. */
	private static final long DANGER_FRESH_TICKS = 24000;
	private static final int DANGER_DISTANCE = 12;
	/** No sign goes up with planks (someone's building) this close. */
	private static final int CLEAR_OF_BUILDINGS = 4;
	/** Only the quite curious try writing without having seen it done. */
	public static final double CURIOUS = 0.65;

	/** Something worth writing here, how much it's worth to others (0 to 1), and the memory it comes from (-1 if none). */
	public record Message(List<String> lines, double worth, long sourceMemoryId) {
		public String text() {
			return SignWords.join(lines);
		}
	}

	/** A sign in sight and what it says. */
	public record SeenSign(BlockPos pos, String text) {
	}

	/** What a newly read sign told the reader. */
	public record Reading(String text, Optional<BlockPos> trees, boolean warns) {
	}

	private Writing() {
	}

	/** Signs within {@code radius} of {@code here} with something written on the front. */
	public static List<SeenSign> signsAround(ServerLevel world, BlockPos here, int radius) {
		List<SeenSign> seen = new ArrayList<>();
		long radiusSq = (long) radius * radius;
		int minChunkX = (here.getX() - radius) >> 4;
		int maxChunkX = (here.getX() + radius) >> 4;
		int minChunkZ = (here.getZ() - radius) >> 4;
		int maxChunkZ = (here.getZ() + radius) >> 4;
		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (be instanceof SignBlockEntity sign && be.getBlockPos().distSqr(here) <= radiusSq) {
						String text = SignWords.join(lines(sign.getFrontText()));
						if (!text.isEmpty()) {
							seen.add(new SeenSign(be.getBlockPos().immutable(), text));
						}
					}
				}
			}
		}
		return seen;
	}

	private static List<String> lines(SignText text) {
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			lines.add(text.getMessage(i, false).getString());
		}
		return lines;
	}

	/**
	 * Reads the signs close by that it hasn't read: each becomes a memory of
	 * having read it, traced to whoever wrote it. Reading someone else's sign
	 * is how it first learns that writing can be done. Returns what the new
	 * ones said, for the embodiment to act on (trees to go to, a warning).
	 */
	public static List<Reading> readAround(AgentEntity self, ServerLevel world, AgentMind mind, long tick, EventLog log) {
		List<Reading> read = new ArrayList<>();
		Library library = null;
		for (SeenSign sign : signsAround(world, self.blockPosition(), READ_DISTANCE)) {
			if (library == null) {
				library = Library.get(world);
			}
			Library.Document doc = library.found(sign.pos(), sign.text(), tick);
			UUID self_ = mind.identity().id();
			if (self_.equals(doc.authorId()) || mind.readDocuments().contains(doc.id())) {
				continue;
			}
			Optional<int[]> step = SignWords.treesAt(sign.text());
			boolean warns = SignWords.warns(sign.text());
			double importance = step.isPresent() ? 0.55 : warns ? 0.5 : 0.4;
			Optional<MemoryEntry> memory = mind.readWriting(tick, doc.id(), sign.text(), doc.authorId(), doc.authorMemoryId(),
					importance);
			if (memory.isEmpty()) {
				continue;
			}
			String name = mind.identity().name();
			String by = doc.authorId() == null ? "" : " by " + doc.authorName();
			log.append(tick, EventType.READ, doc.authorId() == null ? List.of(self_) : List.of(self_, doc.authorId()),
					name + " read a sign" + by + ": \"" + sign.text() + "\".", List.of());
			if (!mind.recipeBook().knowsPractice(RecipeBook.WRITING)) {
				mind.recipeBook().learnPractice(RecipeBook.WRITING,
						new RecipeBook.Learned("saw", doc.authorName(), doc.authorId(), tick));
				mind.perceive(tick, "Marks on a sign can hold words for whoever passes by. I could leave word for others too.",
						0.6, Set.of());
				log.append(tick, EventType.MILESTONE, List.of(self_),
						name + " learned that words can be left on a sign, from reading one" + by + ".", List.of());
			}
			read.add(new Reading(sign.text(), step.map(s -> sign.pos().offset(s[0], 0, s[1])), warns));
		}
		return read;
	}

	/** Whether it would write at all: it knows how, has heard it can be done, or is curious enough to try. */
	public static boolean wouldWrite(AgentMind mind) {
		RecipeBook book = mind.recipeBook();
		return book.knowsPractice(RecipeBook.WRITING) || book.heardOfPractice(RecipeBook.WRITING)
				|| mind.personality().curiosity() > CURIOUS;
	}

	/** Two planks, or a log to split for them, make a sign. */
	public static boolean hasMaterials(AgentMind mind) {
		return planks(mind).filter(id -> mind.countOf(id) >= 2).isPresent() || log(mind).isPresent();
	}

	/**
	 * What, if anything, is worth writing where it stands, from what it
	 * knows: a warning where monsters came for it lately at night, word of
	 * the trees near its home when none are in sight here. Nothing when a
	 * sign close by already says as much.
	 *
	 * @param woods       where it has seen standing trees lately
	 * @param treeInSight whether any tree is in sight now
	 * @param home        where its home is, if it has one ({@code null} if not)
	 * @param attackedAt  where a monster last came for it at night, if it did ({@code null} if not)
	 */
	public static Optional<Message> whatToWrite(AgentEntity self, ServerLevel world, AgentMind mind, long tick,
			Collection<BlockPos> woods, boolean treeInSight, BlockPos home, Attack attackedAt) {
		BlockPos here = self.blockPosition();
		if (spotFor(world, here).isEmpty()) {
			// Nowhere here a sign could stand: nothing to write, or it would keep trying in vain.
			return Optional.empty();
		}
		List<SeenSign> nearby = signsAround(world, here, SAME_NEWS_DISTANCE);
		String name = mind.identity().name();
		long day = tick / 24000L + 1;
		if (attackedAt != null && tick - attackedAt.tick() < DANGER_FRESH_TICKS
				&& attackedAt.pos().distSqr(here) <= (long) DANGER_DISTANCE * DANGER_DISTANCE
				&& nearby.stream().noneMatch(s -> SignWords.warns(s.text()))) {
			return Optional.of(new Message(SignWords.danger(attackedAt.monster(), name, day), 0.6, attackedAt.memoryId()));
		}
		boolean nearHome = home != null && home.distSqr(here) <= (long) NEAR_HOME * NEAR_HOME;
		if (nearHome && !treeInSight && nearby.stream().noneMatch(s -> SignWords.treesAt(s.text()).isPresent())) {
			long farSq = (long) WOODS_WORTH_POINTING_TO * WOODS_WORTH_POINTING_TO;
			Optional<BlockPos> nearest = woods.stream()
					.min(java.util.Comparator.comparingDouble(w -> w.distSqr(here)));
			if (nearest.isPresent() && nearest.get().distSqr(here) >= farSq) {
				BlockPos w = nearest.get();
				return Optional.of(new Message(SignWords.trees(w.getX() - here.getX(), w.getZ() - here.getZ(), name, day), 0.7,
						woodsMemory(mind, tick)));
			}
		}
		return Optional.empty();
	}

	/** Where a monster came for it at night, which monster, and the memory of it. */
	public record Attack(BlockPos pos, long tick, String monster, long memoryId) {
	}

	private static long woodsMemory(AgentMind mind, long tick) {
		return mind.memories().retrieve(tick, 200).stream()
				.filter(m -> m.description().contains("stand of trees"))
				.findFirst().map(MemoryEntry::id).orElse(-1L);
	}

	/**
	 * Puts the sign up beside it, facing it, from two planks (or a log). The
	 * first time, if it had never seen writing work, it sees that the words
	 * stay and now knows. Returns whether a sign went up.
	 */
	public static boolean write(AgentEntity self, ServerLevel world, AgentMind mind, Message message, long tick, EventLog log) {
		Optional<BlockPos> spot = spotFor(world, self.blockPosition());
		if (spot.isEmpty() || !takeMaterials(mind, tick)) {
			return false;
		}
		BlockState state = Blocks.OAK_SIGN.defaultBlockState()
				.setValue(StandingSignBlock.ROTATION, Mth.floor((self.getYRot() + 180.0F) * 16.0F / 360.0F + 0.5) & 15);
		world.setBlockAndUpdate(spot.get(), state);
		if (!(world.getBlockEntity(spot.get()) instanceof SignBlockEntity sign)) {
			return false;
		}
		SignText text = new SignText();
		for (int i = 0; i < 4 && i < message.lines().size(); i++) {
			text = text.setMessage(i, Component.literal(message.lines().get(i)));
		}
		sign.setText(text, true);
		sign.setChanged();
		world.sendBlockUpdated(spot.get(), state, state, 3);

		String name = mind.identity().name();
		UUID id = mind.identity().id();
		boolean untried = !mind.recipeBook().knowsPractice(RecipeBook.WRITING);
		MemoryEntry wrote = mind.perceive(tick, "I put up a sign saying \"" + message.text() + "\", so others would know.",
				0.45, Set.of());
		long source = message.sourceMemoryId() >= 0 ? message.sourceMemoryId() : wrote.id();
		Library library = Library.get(world);
		boolean first = library.documents().stream().noneMatch(d -> d.authorId() != null);
		library.written(spot.get(), message.text(), id, name, source, tick);
		log.append(tick, EventType.WROTE, List.of(id), name + " put up a sign: \"" + message.text() + "\".", List.of());
		if (untried) {
			mind.recipeBook().learnPractice(RecipeBook.WRITING, new RecipeBook.Learned("made", "", null, tick));
			mind.perceive(tick, "I scratched words onto a sign and they stayed. Anyone passing can read them now.", 0.7, Set.of());
			log.append(tick, EventType.MILESTONE, List.of(id), name + (first
					? " wrote the first words any of them had written, on a sign."
					: " tried writing on a sign for the first time, and saw the words stay."), List.of());
		}
		return true;
	}

	/**
	 * Open ground beside it with something solid underneath, and no
	 * building close by: a sign standing where a wall should go would leave
	 * a builder unable to finish.
	 */
	private static Optional<BlockPos> spotFor(ServerLevel world, BlockPos here) {
		for (BlockPos near : BlockPos.betweenClosed(here.offset(-CLEAR_OF_BUILDINGS, -1, -CLEAR_OF_BUILDINGS),
				here.offset(CLEAR_OF_BUILDINGS, 3, CLEAR_OF_BUILDINGS))) {
			if (world.getBlockState(near).is(BlockTags.PLANKS)) {
				return Optional.empty();
			}
		}
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos spot = here.relative(side);
			if (world.getBlockState(spot).isAir()
					&& Blocks.OAK_SIGN.defaultBlockState().canSurvive(world, spot)) {
				return Optional.of(spot.immutable());
			}
		}
		return Optional.empty();
	}

	private static boolean takeMaterials(AgentMind mind, long tick) {
		Optional<String> planks = planks(mind).filter(id -> mind.countOf(id) >= 2);
		if (planks.isPresent()) {
			return mind.takeItem(planks.get(), 2);
		}
		Optional<String> log = log(mind);
		if (log.isEmpty() || !mind.takeItem(log.get(), 1)) {
			return false;
		}
		// A log splits into four planks: two go into the sign, the rest are kept.
		ItemKinds.planksFor(log.get()).ifPresent(p -> mind.receiveItem(tick, p, 2));
		return true;
	}

	private static Optional<String> planks(AgentMind mind) {
		return mind.possessions().stream().filter(p -> p.quantity() > 0 && p.itemId().endsWith("_planks"))
				.map(Possession::itemId).findFirst();
	}

	private static Optional<String> log(AgentMind mind) {
		return mind.possessions().stream().filter(p -> p.quantity() > 0 && ItemKinds.isLog(p.itemId()))
				.map(Possession::itemId).findFirst();
	}
}
