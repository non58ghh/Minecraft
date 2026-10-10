package com.aicivilization.world;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * Where the dead left their belongings: a chest where each one fell, and
 * whose it was. The chest is a real block anyone can open; this record is
 * only what lets an agent who comes upon it know whose things these are
 * (as a person would, from what's in it and where). Agents never consult
 * the list from afar: only a chest within sight of where they stand.
 */
public final class Remains extends SavedData {

	private static final Codec<Remains> CODEC = CompoundTag.CODEC.xmap(Remains::fromTag, Remains::toTag);

	public static final SavedDataType<Remains> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("aicivilization", "remains"),
			Remains::new,
			CODEC,
			DataFixTypes.LEVEL
	);

	/** One agent's belongings: where, whose, how they died, and when. */
	public record Left(BlockPos pos, UUID deadId, String deadName, String how, long tick) {
	}

	private final List<Left> left = new ArrayList<>();

	public static Remains get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	public List<Left> all() {
		return Collections.unmodifiableList(left);
	}

	public void add(Left l) {
		left.add(l);
		setDirty();
	}

	/** Its chest has been emptied (or is gone). */
	public void remove(Left l) {
		if (left.remove(l)) {
			setDirty();
		}
	}

	private CompoundTag toTag() {
		CompoundTag nbt = new CompoundTag();
		ListTag list = new ListTag();
		for (Left l : left) {
			CompoundTag t = new CompoundTag();
			t.putInt("x", l.pos().getX());
			t.putInt("y", l.pos().getY());
			t.putInt("z", l.pos().getZ());
			t.store("deadId", UUIDUtil.CODEC, l.deadId());
			t.putString("deadName", l.deadName());
			t.putString("how", l.how());
			t.putLong("tick", l.tick());
			list.add(t);
		}
		nbt.put("left", list);
		return nbt;
	}

	private static Remains fromTag(CompoundTag nbt) {
		Remains remains = new Remains();
		ListTag list = nbt.getListOrEmpty("left");
		for (int i = 0; i < list.size(); i++) {
			CompoundTag t = list.getCompoundOrEmpty(i);
			t.read("deadId", UUIDUtil.CODEC).ifPresent(id -> remains.left.add(new Left(
					new BlockPos(t.getIntOr("x", 0), t.getIntOr("y", 0), t.getIntOr("z", 0)), id,
					t.getStringOr("deadName", ""), t.getStringOr("how", "died"), t.getLongOr("tick", 0))));
		}
		return remains;
	}
}
