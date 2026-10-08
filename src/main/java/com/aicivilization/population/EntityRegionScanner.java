package com.aicivilization.population;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
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

	/** What a scan found, plus enough counts to tell why something wasn't found. */
	public record Result(Map<UUID, Long> found, boolean dirExists, int regionFiles, int chunksRead, int chunksUnreadable) {
	}

	/** Returns packed chunk positions for whichever of {@code wanted} were found. */
	public static Map<UUID, Long> find(Path entitiesDir, Set<UUID> wanted) {
		return scan(entitiesDir, wanted).found();
	}

	public static Result scan(Path entitiesDir, Set<UUID> wanted) {
		Map<UUID, Long> found = new HashMap<>();
		int[] counts = new int[3]; // region files, chunks read, chunks unreadable
		boolean exists = Files.isDirectory(entitiesDir);
		if (wanted.isEmpty() || !exists) {
			return new Result(found, exists, 0, 0, 0);
		}
		try (Stream<Path> files = Files.list(entitiesDir)) {
			for (Path region : files.filter(p -> p.getFileName().toString().endsWith(".mca")).toList()) {
				counts[0]++;
				scanRegion(region, wanted, found, counts);
				if (found.size() == wanted.size()) {
					break;
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Could not list {} while looking for agent bodies.", entitiesDir, e);
		}
		return new Result(found, true, counts[0], counts[1], counts[2]);
	}

	static void scanRegion(Path region, Set<UUID> wanted, Map<UUID, Long> found, int[] counts) {
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
					counts[1]++;
					collect(chunk, wanted, found);
				} else {
					counts[2]++;
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
			// The game's own codecs: gzip, deflate, none, lz4 (whichever the server is set to).
			// Bit 128 = stored in an external .mcc file, which entity chunks almost never need.
			RegionFileVersion version = (compression & 128) != 0 ? null : RegionFileVersion.fromId(compression);
			if (version == null) {
				return null;
			}
			InputStream in = version.wrap(new ByteArrayInputStream(data));
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
