package com.aicivilization.population;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Finds which saved chunk holds given entities by reading a dimension's
 * {@code entities/r.X.Z.mca} region files directly. Used once at startup for
 * agents whose body location was never recorded (worlds saved before the
 * mod tracked it), so {@link AgentChunkLoader} knows which chunks to load.
 * Read-only; unreadable chunks are skipped.
 */
public final class EntityRegionScanner {

	private static final Logger LOGGER = LoggerFactory.getLogger("aicivilization");
	private static final int SECTOR_BYTES = 4096;

	private EntityRegionScanner() {
	}

	/** Returns packed chunk positions for whichever of {@code wanted} were found. */
	public static Map<UUID, Long> find(Path entitiesDir, Set<UUID> wanted) {
		Map<UUID, Long> found = new HashMap<>();
		if (wanted.isEmpty() || !Files.isDirectory(entitiesDir)) {
			return found;
		}
		try (Stream<Path> files = Files.list(entitiesDir)) {
			for (Path region : files.filter(p -> p.getFileName().toString().endsWith(".mca")).toList()) {
				scanRegion(region, wanted, found);
				if (found.size() == wanted.size()) {
					break;
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Could not list {} while looking for agent bodies.", entitiesDir, e);
		}
		return found;
	}

	static void scanRegion(Path region, Set<UUID> wanted, Map<UUID, Long> found) {
		try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
			if (file.length() < SECTOR_BYTES) {
				return;
			}
			int[] offsets = new int[1024];
			for (int i = 0; i < offsets.length; i++) {
				offsets[i] = file.readInt();
			}
			for (int location : offsets) {
				int sector = location >>> 8;
				if (sector == 0) {
					continue;
				}
				CompoundTag chunk = readChunk(file, (long) sector * SECTOR_BYTES);
				if (chunk != null) {
					collect(chunk, wanted, found);
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Could not read region file {} while looking for agent bodies.", region, e);
		}
	}

	private static CompoundTag readChunk(RandomAccessFile file, long position) {
		try {
			if (position + 5 > file.length()) {
				return null;
			}
			file.seek(position);
			int length = file.readInt();
			int compression = file.readUnsignedByte();
			if (length <= 1 || position + 4 + length > file.length()) {
				return null;
			}
			byte[] data = new byte[length - 1];
			file.readFully(data);
			InputStream raw = new ByteArrayInputStream(data);
			InputStream in = switch (compression) {
				case 1 -> new GZIPInputStream(raw);
				case 2 -> new InflaterInputStream(raw);
				case 3 -> raw;
				default -> null; // LZ4, external .mcc files: not used for small entity chunks
			};
			if (in == null) {
				return null;
			}
			try (DataInputStream nbt = new DataInputStream(new BufferedInputStream(in))) {
				return NbtIo.read(nbt, NbtAccounter.unlimitedHeap());
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	static void collect(CompoundTag chunk, Set<UUID> wanted, Map<UUID, Long> found) {
		int[] pos = chunk.getIntArray("Position").orElse(null);
		if (pos == null || pos.length != 2) {
			return;
		}
		ListTag entities = chunk.getListOrEmpty("Entities");
		for (int i = 0; i < entities.size(); i++) {
			int[] id = entities.getCompoundOrEmpty(i).getIntArray("UUID").orElse(null);
			if (id != null && id.length == 4) {
				UUID uuid = UUIDUtil.uuidFromIntArray(id);
				if (wanted.contains(uuid)) {
					found.put(uuid, ChunkPos.pack(pos[0], pos[1]));
				}
			}
		}
	}
}
