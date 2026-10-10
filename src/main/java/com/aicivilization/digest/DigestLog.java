package com.aicivilization.digest;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** The {@link DigestBook}, saved with the world so the account survives a restart. */
public final class DigestLog extends SavedData {

	private static final Codec<DigestLog> CODEC = CompoundTag.CODEC.xmap(DigestLog::fromTag, DigestLog::toTag);

	public static final SavedDataType<DigestLog> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("aicivilization", "digest"),
			DigestLog::new,
			CODEC,
			DataFixTypes.LEVEL
	);

	private DigestBook book = new DigestBook();

	public static DigestLog get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	public DigestBook book() {
		return book;
	}

	public void changed() {
		setDirty();
	}

	private CompoundTag toTag() {
		CompoundTag nbt = new CompoundTag();
		nbt.putString("json", book.save());
		return nbt;
	}

	private static DigestLog fromTag(CompoundTag nbt) {
		DigestLog log = new DigestLog();
		log.book = DigestBook.load(nbt.getStringOr("json", ""));
		return log;
	}
}
