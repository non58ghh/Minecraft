package com.aicivilization.behavior;

import com.aicivilization.AICivilizationMod;
import com.aicivilization.action.ItemKinds;
import com.aicivilization.action.PhysicalActions;
import com.aicivilization.entity.AgentEntity;
import com.aicivilization.events.EventLog;
import com.aicivilization.events.EventType;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Home;
import com.aicivilization.mind.Places;
import com.aicivilization.mind.Possession;
import com.aicivilization.population.PopulationRegistry;
import com.aicivilization.world.Remains;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * What a death leaves behind, and how the living come to know of it. The
 * dead's belongings stay where they fell, in a chest anyone can open; their
 * home stays standing. Those who see it happen know at once; others learn
 * of it by coming upon the belongings or by being told. Someone without a
 * home who knows the owner is gone may take an empty home as their own.
 * Nothing is announced to everyone: a death is known only as far as it's
 * seen and spoken of.
 */
public final class Mourning {

	/** How far off a death can be seen (with a clear line of sight). */
	static final double WITNESS_RANGE = 24;
	/** Belongings this close are noticed. */
	static final int FIND_RANGE = 6;
	/** An empty home is taken by someone standing this close to it. */
	static final int CLAIM_RANGE = 8;
	private static final int CHEST_SLOTS = 27;

	private Mourning() {
	}

	/** "was killed by a zombie" and the like, for the record and for those who hear of it. */
	public static void onDeath(AgentEntity dead, AgentMind mind, String how, ServerLevel world, long tick) {
		EventLog log = EventLog.get(world);
		for (AgentEntity witness : world.getEntities(AICivilizationMod.AGENT_ENTITY_TYPE,
				dead.getBoundingBox().inflate(WITNESS_RANGE), e -> e != dead && e.isAlive() && e.hasLineOfSight(dead))) {
			AgentMind seer = witness.mind();
			if (seer != null && seer.isAlive()
					&& seer.learnOfDeath(tick, mind.identity().id(), mind.identity().name(), how, AgentMind.DeathNews.SAW,
							null, null)) {
				log.append(tick, EventType.PERCEIVED, List.of(seer.identity().id(), mind.identity().id()),
						seer.identity().name() + " saw " + mind.identity().name() + " die.", List.of());
			}
		}
		leaveBelongings(dead, mind, how, world, tick, log);
	}

	/** Everything it carried goes into a chest where it fell (or, with nowhere to set one, onto the ground). */
	private static void leaveBelongings(AgentEntity dead, AgentMind mind, String how, ServerLevel world, long tick,
			EventLog log) {
		List<ItemStack> stacks = new ArrayList<>();
		for (Possession p : List.copyOf(mind.possessions())) {
			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(p.itemId()));
			if (p.quantity() <= 0 || item.isEmpty() || !mind.takeItem(p.itemId(), p.quantity())) {
				continue;
			}
			int left = p.quantity();
			int max = Math.max(1, item.get().getDefaultMaxStackSize());
			while (left > 0) {
				int n = Math.min(left, max);
				stacks.add(new ItemStack(item.get(), n));
				left -= n;
			}
		}
		if (stacks.isEmpty()) {
			return;
		}
		Optional<BlockPos> spot = chestSpot(world, dead.blockPosition());
		if (spot.isPresent()) {
			world.setBlockAndUpdate(spot.get(), Blocks.CHEST.defaultBlockState());
			if (world.getBlockEntity(spot.get()) instanceof ChestBlockEntity chest) {
				for (int i = 0; i < stacks.size() && i < CHEST_SLOTS; i++) {
					chest.setItem(i, stacks.get(i));
				}
				chest.setChanged();
				Remains.get(world).add(new Remains.Left(spot.get().immutable(), mind.identity().id(), mind.identity().name(),
						how, tick));
				log.append(tick, EventType.ACTION, List.of(mind.identity().id()),
						mind.identity().name() + "'s belongings lie where they fell.", List.of());
				stacks = stacks.subList(Math.min(stacks.size(), CHEST_SLOTS), stacks.size());
			}
		}
		for (ItemStack rest : stacks) {
			dead.spawnAtLocation(world, rest);
		}
	}

	/** Open ground where it fell, or close by: air with something solid underneath, not in water. */
	private static Optional<BlockPos> chestSpot(ServerLevel world, BlockPos at) {
		for (int r = 0; r <= 2; r++) {
			for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -1, -r), at.offset(r, 1, r))) {
				if (world.getBlockState(p).isAir() && world.getFluidState(p).isEmpty()
						&& world.getBlockState(p.below()).isFaceSturdy(world, p.below(), Direction.UP)) {
					return Optional.of(p.immutable());
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Belongings left close by: whose they are tells it that person is dead,
	 * if it didn't know; and it takes what it can carry. An emptied (or
	 * broken) chest is forgotten.
	 */
	static void lookAround(AgentEntity self, AgentMind mind, ServerLevel world, long tick, EventLog log) {
		Remains remains = Remains.get(world);
		BlockPos here = self.blockPosition();
		for (Remains.Left l : List.copyOf(remains.all())) {
			if (l.pos().distSqr(here) > (long) FIND_RANGE * FIND_RANGE || l.deadId().equals(mind.identity().id())) {
				continue;
			}
			if (!(world.getBlockEntity(l.pos()) instanceof ChestBlockEntity chest)) {
				remains.remove(l);
				continue;
			}
			if (mind.learnOfDeath(tick, l.deadId(), l.deadName(), l.how(), AgentMind.DeathNews.FOUND, null, null)) {
				log.append(tick, EventType.PERCEIVED, List.of(mind.identity().id(), l.deadId()),
						mind.identity().name() + " came upon " + l.deadName() + "'s belongings and knew "
								+ l.deadName() + " was dead.", List.of());
			}
			int taken = 0;
			for (int slot = 0; slot < chest.getContainerSize(); slot++) {
				ItemStack stack = chest.getItem(slot);
				if (stack.isEmpty()) {
					continue;
				}
				String id = ItemKinds.idOf(stack);
				int n = Math.min(stack.getCount(), PhysicalActions.carryLimit(id) - mind.countOf(id));
				if (n <= 0) {
					continue;
				}
				mind.receiveItem(tick, id, n);
				stack.shrink(n);
				chest.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
				taken += n;
			}
			if (taken > 0) {
				chest.setChanged();
				mind.perceive(tick, "I took what I could use from " + l.deadName() + "'s belongings.", 0.45,
						Set.of(l.deadId()));
				log.append(tick, EventType.ACTION, List.of(mind.identity().id(), l.deadId()),
						mind.identity().name() + " took what they could use from " + l.deadName() + "'s belongings.",
						List.of());
			}
			if (chest.isEmpty()) {
				remains.remove(l);
			}
		}
	}

	/**
	 * Without a home of its own, standing by the home of someone it knows to
	 * be dead, still standing: it makes it its own.
	 */
	static boolean claimEmptyHome(AgentEntity self, AgentMind mind, ServerLevel world, long tick, EventLog log) {
		if (mind.home().isPresent() || mind.project().isPresent() || mind.isChild(tick)) {
			return false;
		}
		BlockPos here = self.blockPosition();
		for (AgentMind owner : PopulationRegistry.get(world).population().allMinds()) {
			if (owner.isAlive() || owner.home().isEmpty() || !mind.knowsDead(owner.identity().id())) {
				continue;
			}
			Home home = owner.home().get();
			BlockPos origin = new BlockPos(home.x(), home.y(), home.z());
			if (origin.distSqr(here) > (long) CLAIM_RANGE * CLAIM_RANGE
					|| PhysicalActions.damage(world, origin, home.design()) > 0.25) {
				continue;
			}
			owner.loseHome();
			mind.setHome(new Home(home.x(), home.y(), home.z(), home.design(), tick));
			mind.places().forget(Places.Kind.HOME, home.x(), home.y(), home.z(), 0);
			String name = owner.identity().name();
			mind.perceive(tick, name + "'s home stood empty, so I made it mine.", 0.7, Set.of(owner.identity().id()));
			log.append(tick, EventType.MILESTONE, List.of(mind.identity().id(), owner.identity().id()),
					mind.identity().name() + " moved into the home " + name + " left empty.", List.of());
			return true;
		}
		return false;
	}

	/**
	 * In conversation: a death it knows of that the other doesn't. Told
	 * second-hand too: unlike news, a death is passed on.
	 */
	static boolean tellOfDeath(AgentMind self, AgentMind other, long tick, EventLog log) {
		for (var dead : self.knownDead().entrySet()) {
			if (other.knowsDead(dead.getKey()) || dead.getKey().equals(other.identity().id())) {
				continue;
			}
			AgentMind.KnownDeath death = dead.getValue();
			if (other.learnOfDeath(tick, dead.getKey(), death.name(), death.how(), AgentMind.DeathNews.TOLD,
					self.identity().id(), self.identity().name())) {
				log.append(tick, EventType.TOLD, List.of(self.identity().id(), other.identity().id(), dead.getKey()),
						self.identity().name() + " told " + other.identity().name() + " that " + death.name() + " "
								+ death.how() + ".", List.of());
				return true;
			}
		}
		return false;
	}
}
