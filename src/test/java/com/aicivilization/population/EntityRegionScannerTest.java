package com.aicivilization.population;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityRegionScannerTest {

	@TempDir
	Path dir;

	private static byte[] zlibNbt(CompoundTag tag) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
			NbtIo.write(tag, out);
		}
		return bytes.toByteArray();
	}

	private static CompoundTag entityChunk(int x, int z, UUID... ids) {
		CompoundTag chunk = new CompoundTag();
		chunk.put("Position", new IntArrayTag(new int[] {x, z}));
		ListTag entities = new ListTag();
		for (UUID id : ids) {
			CompoundTag e = new CompoundTag();
			e.putString("id", "aicivilization:agent");
			e.put("UUID", new IntArrayTag(UUIDUtil.uuidToIntArray(id)));
			entities.add(e);
		}
		chunk.put("Entities", entities);
		return chunk;
	}

	/** Writes a region file with each chunk in its own sector, starting at sector 2. */
	private void writeRegion(String name, CompoundTag... chunks) throws Exception {
		try (RandomAccessFile file = new RandomAccessFile(dir.resolve(name).toFile(), "rw")) {
			file.setLength(4096L * (2 + chunks.length));
			for (int i = 0; i < chunks.length; i++) {
				int sector = 2 + i;
				file.seek(i * 4L);
				file.writeInt(sector << 8 | 1);
				byte[] data = zlibNbt(chunks[i]);
				file.seek(sector * 4096L);
				file.writeInt(data.length + 1);
				file.writeByte(2);
				file.write(data);
			}
		}
	}

	@Test
	void findsTheChunkHoldingEachWantedEntity() throws Exception {
		UUID nadia = UUID.randomUUID();
		UUID osric = UUID.randomUUID();
		UUID stranger = UUID.randomUUID();
		writeRegion("r.0.0.mca", entityChunk(3, -2, nadia, stranger), entityChunk(5, 7));
		writeRegion("r.-1.0.mca", entityChunk(-4, 1, osric));

		Map<UUID, Long> found = EntityRegionScanner.find(dir, Set.of(nadia, osric));

		assertEquals(2, found.size());
		assertEquals(ChunkPos.pack(3, -2), found.get(nadia));
		assertEquals(ChunkPos.pack(-4, 1), found.get(osric));
	}

	@Test
	void missingDirectoryOrUnknownIdsFindNothing() throws Exception {
		assertTrue(EntityRegionScanner.find(dir.resolve("nope"), Set.of(UUID.randomUUID())).isEmpty());
		writeRegion("r.0.0.mca", entityChunk(0, 0, UUID.randomUUID()));
		assertTrue(EntityRegionScanner.find(dir, Set.of(UUID.randomUUID())).isEmpty());
	}

	@Test
	void ignoresFilesThatAreNotRegions() throws Exception {
		java.nio.file.Files.writeString(dir.resolve("r.0.0.mca"), "not a region file");
		assertTrue(EntityRegionScanner.find(dir, Set.of(UUID.randomUUID())).isEmpty());
	}
}
