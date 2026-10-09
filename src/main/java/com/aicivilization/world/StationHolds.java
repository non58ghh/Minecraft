package com.aicivilization.world;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * Which furnace (or other station) is in use by whom, so two agents don't
 * load the same one: whoever put their things in holds it until they've
 * taken the results out, or until the hold runs out. An agent only finds
 * out a furnace is taken by going to it, as anyone would.
 */
public final class StationHolds {

	private record Hold(UUID holder, long untilTick) {
	}

	private static final Map<BlockPos, Hold> HOLDS = new HashMap<>();

	private StationHolds() {
	}

	/** Whether someone other than {@code agent} is using the station at {@code pos}. */
	public static boolean heldByOther(BlockPos pos, UUID agent, long tick) {
		Hold hold = HOLDS.get(pos);
		if (hold == null || hold.untilTick() < tick) {
			HOLDS.remove(pos);
			return false;
		}
		return !hold.holder().equals(agent);
	}

	public static void hold(BlockPos pos, UUID agent, long untilTick) {
		if (HOLDS.size() > 1024) {
			HOLDS.clear();
		}
		HOLDS.put(pos.immutable(), new Hold(agent, untilTick));
	}

	public static void release(BlockPos pos, UUID agent) {
		Hold hold = HOLDS.get(pos);
		if (hold != null && hold.holder().equals(agent)) {
			HOLDS.remove(pos);
		}
	}
}
