package com.aicivilization.observer;

import com.google.gson.JsonObject;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * The land around the agents as a Minecraft map item would draw it: for each
 * cell, the top block's map colour and its height (or, on water, how deep
 * the water is), so the page can shade slopes and depths the way the game
 * does. Only chunks already loaded are read; the rest stay blank, as
 * unexplored parts of a map do. Observer-side only: agents never see it.
 */
public final class TerrainMap {

	/** Land drawn around everyone, in blocks. */
	static final int MARGIN = 24;
	/** Cells across the longer side, at most; each cell is one or more blocks. */
	static final int MAX_CELLS = 160;
	private static final int MAX_WATER_DEPTH = 15;
	private static final int MAX_DIG_FOR_COLOR = 8;

	private TerrainMap() {
	}

	/** The area to draw: corner, block size of a cell, and cells across and down. */
	record Bounds(int x0, int z0, int step, int w, int h) {
	}

	/** Covers every point with {@link #MARGIN} to spare, in at most {@link #MAX_CELLS} cells a side. */
	static Bounds bounds(List<double[]> points) {
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (double[] p : points) {
			minX = Math.min(minX, p[0]);
			maxX = Math.max(maxX, p[0]);
			minZ = Math.min(minZ, p[1]);
			maxZ = Math.max(maxZ, p[1]);
		}
		int x0 = (int) Math.floor(minX) - MARGIN, z0 = (int) Math.floor(minZ) - MARGIN;
		int spanX = (int) Math.ceil(maxX) + MARGIN - x0 + 1, spanZ = (int) Math.ceil(maxZ) + MARGIN - z0 + 1;
		int step = Math.max(1, (int) Math.ceil(Math.max(spanX, spanZ) / (double) MAX_CELLS));
		return new Bounds(x0, z0, step, (spanX + step - 1) / step, (spanZ + step - 1) / step);
	}

	/**
	 * Samples the middle of each cell. Two bytes a cell: the map colour id
	 * (0, none, where nothing is loaded) and the height above the world's
	 * floor (capped at 255), or for water its depth.
	 */
	public static JsonObject render(ServerLevel world, List<double[]> points) {
		JsonObject o = new JsonObject();
		if (points.isEmpty()) {
			return o;
		}
		Bounds b = bounds(points);
		byte[] cells = new byte[b.w() * b.h() * 2];
		Map<Integer, Integer> palette = new TreeMap<>();
		int floor = world.getMinY();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int j = 0; j < b.h(); j++) {
			for (int i = 0; i < b.w(); i++) {
				int x = b.x0() + i * b.step() + b.step() / 2, z = b.z0() + j * b.step() + b.step() / 2;
				LevelChunk chunk = world.getChunkSource().getChunkNow(x >> 4, z >> 4);
				if (chunk == null) {
					continue;
				}
				int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
				BlockState state = chunk.getBlockState(pos.set(x, y, z));
				MapColor color = state.getMapColor(world, pos);
				for (int dig = 0; color == MapColor.NONE && dig < MAX_DIG_FOR_COLOR && y > floor; dig++) {
					state = chunk.getBlockState(pos.set(x, --y, z));
					color = state.getMapColor(world, pos);
				}
				int second = Math.min(255, Math.max(0, y - floor));
				if (state.getFluidState().is(FluidTags.WATER)) {
					int depth = 1;
					while (depth < MAX_WATER_DEPTH && chunk.getFluidState(x, y - depth, z).is(FluidTags.WATER)) {
						depth++;
					}
					second = depth;
					color = MapColor.WATER;
				}
				int at = (j * b.w() + i) * 2;
				cells[at] = (byte) color.id;
				cells[at + 1] = (byte) second;
				if (color != MapColor.NONE) {
					palette.putIfAbsent(color.id, color.col);
				}
			}
		}
		o.addProperty("x0", b.x0());
		o.addProperty("z0", b.z0());
		o.addProperty("step", b.step());
		o.addProperty("w", b.w());
		o.addProperty("h", b.h());
		o.addProperty("water", MapColor.WATER.id);
		JsonObject colors = new JsonObject();
		palette.forEach((id, col) -> colors.addProperty(String.valueOf(id), String.format("#%06x", col & 0xFFFFFF)));
		o.add("palette", colors);
		o.addProperty("cells", Base64.getEncoder().encodeToString(cells));
		return o;
	}
}
