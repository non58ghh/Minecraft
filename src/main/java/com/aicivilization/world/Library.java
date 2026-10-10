package com.aicivilization.world;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Every piece of writing agents have read or written: where it stands,
 * what it says, and who wrote it (an agent, with the memory it came from,
 * or nobody known for a player's sign). This is bookkeeping for
 * provenance and the observer, like the event log: agents never consult
 * it. A reader only ever gets the words on the sign in front of it; the
 * author recorded here is what lets {@code Provenance.Read} trace those
 * words back to the memory they were written from.
 */
public final class Library extends SavedData {

	private static final Codec<Library> CODEC = CompoundTag.CODEC.xmap(Library::fromTag, Library::toTag);

	public static final SavedDataType<Library> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("aicivilization", "library"),
			Library::new,
			CODEC,
			DataFixTypes.LEVEL
	);

	/** One piece of writing. {@code authorId} is null and {@code authorMemoryId} -1 when no agent wrote it. */
	public record Document(long id, BlockPos pos, String text, UUID authorId, String authorName, long authorMemoryId,
			long tick) {
	}

	private final List<Document> documents = new ArrayList<>();
	private long nextId = 1;

	public static Library get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	public List<Document> documents() {
		return Collections.unmodifiableList(documents);
	}

	/** An agent wrote this here, from that memory of theirs. */
	public Document written(BlockPos pos, String text, UUID authorId, String authorName, long authorMemoryId, long tick) {
		Document doc = new Document(nextId++, pos.immutable(), text, authorId, authorName, authorMemoryId, tick);
		documents.add(doc);
		setDirty();
		return doc;
	}

	/**
	 * The writing standing at {@code pos} that says {@code text}: the one on
	 * record, or (the first time anyone reads a sign no agent wrote, or one
	 * whose words have changed) a new record with no known author.
	 */
	public Document found(BlockPos pos, String text, long tick) {
		Optional<Document> known = documents.stream()
				.filter(d -> d.pos().equals(pos) && d.text().equals(text))
				.findFirst();
		if (known.isPresent()) {
			return known.get();
		}
		Document doc = new Document(nextId++, pos.immutable(), text, null, "", -1, tick);
		documents.add(doc);
		setDirty();
		return doc;
	}

	private CompoundTag toTag() {
		CompoundTag nbt = new CompoundTag();
		ListTag list = new ListTag();
		for (Document d : documents) {
			CompoundTag t = new CompoundTag();
			t.putLong("id", d.id());
			t.putLong("pos", d.pos().asLong());
			t.putString("text", d.text());
			if (d.authorId() != null) {
				t.store("author", UUIDUtil.CODEC, d.authorId());
			}
			t.putString("authorName", d.authorName());
			t.putLong("authorMemory", d.authorMemoryId());
			t.putLong("tick", d.tick());
			list.add(t);
		}
		nbt.put("documents", list);
		nbt.putLong("nextId", nextId);
		return nbt;
	}

	private static Library fromTag(CompoundTag nbt) {
		Library library = new Library();
		ListTag list = nbt.getListOrEmpty("documents");
		for (int i = 0; i < list.size(); i++) {
			CompoundTag t = list.getCompoundOrEmpty(i);
			library.documents.add(new Document(t.getLongOr("id", 0), BlockPos.of(t.getLongOr("pos", 0)),
					t.getStringOr("text", ""), t.read("author", UUIDUtil.CODEC).orElse(null), t.getStringOr("authorName", ""),
					t.getLongOr("authorMemory", -1), t.getLongOr("tick", 0)));
		}
		library.nextId = Math.max(nbt.getLongOr("nextId", 1),
				library.documents.stream().mapToLong(Document::id).max().orElse(0) + 1);
		return library;
	}
}
