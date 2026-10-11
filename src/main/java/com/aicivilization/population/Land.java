package com.aicivilization.population;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Asking for land without waiting on it. Loading a chunk on the spot means
 * generating it there and then if nobody has been there, and on a small
 * server a few of those in one tick stall it past its watchdog. So land is
 * asked for (it loads in the background) and looked at on a later tick,
 * once it's there.
 */
final class Land {

	private Land() {
	}

	/** Whether the chunk holding block x, z is loaded now; if not, asks for it to be loaded in the background. */
	static boolean ready(ServerLevel world, int x, int z) {
		if (world.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) {
			return true;
		}
		// A portal ticket times out by itself, so a spot given up on doesn't stay loaded.
		world.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(x >> 4, z >> 4), 1);
		return false;
	}

	/** The ground at x, z (whose chunk must be loaded): the block above the surface, unless it's water or lava. */
	static BlockPos dryGround(ServerLevel world, int x, int z) {
		BlockPos top = new BlockPos(x, world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
		return world.getFluidState(top.below()).isEmpty() && world.getFluidState(top).isEmpty() ? top : null;
	}
}
