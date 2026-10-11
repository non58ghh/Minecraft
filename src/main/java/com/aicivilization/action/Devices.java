package com.aicivilization.action;

import com.aicivilization.entity.AgentEntity;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Invention;
import com.aicivilization.mind.KnownInvention;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;

/**
 * Every invention that worked and still stands. They keep working as real
 * redstone: anyone or anything that steps on the plate sets them off, and
 * the inventor hears about it when nearby ("my Door Bell went off"). People
 * passing one remember it, and can build it themselves or improve on it. One
 * that's broken up is forgotten here, though not by those who saw it.
 */
public final class Devices extends SavedData {

	private static final Codec<Devices> CODEC = CompoundTag.CODEC.xmap(Devices::fromTag, Devices::toTag);

	public static final SavedDataType<Devices> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("aicivilization", "devices"),
			Devices::new,
			CODEC,
			DataFixTypes.LEVEL
	);

	public static final int CHECK_EVERY_TICKS = 10;
	/** Close enough to a device to see how it's made. */
	static final int SEES = 6;
	/** Close enough to its own device to hear it go off. */
	static final int HEARS = 48;
	/** At most one "it went off" a minute per device. */
	static final long QUIET_TICKS = 1200;

	/** A device standing in the world. */
	public static final class Device {
		final Invention invention;
		final BlockPos output;
		final BlockPos center;
		final UUID inventor;
		final String inventorName;
		final long built;
		String lastState;
		int activations;
		long lastHeard;

		Device(Invention invention, BlockPos output, BlockPos center, UUID inventor, String inventorName, long built) {
			this.invention = invention;
			this.output = output;
			this.center = center;
			this.inventor = inventor;
			this.inventorName = inventorName;
			this.built = built;
		}

		public Invention invention() {
			return invention;
		}

		public BlockPos center() {
			return center;
		}

		public String inventorName() {
			return inventorName;
		}

		public int activations() {
			return activations;
		}

		public long built() {
			return built;
		}
	}

	private final List<Device> devices = new ArrayList<>();

	public static Devices get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	public List<Device> all() {
		return List.copyOf(devices);
	}

	void add(Invention invention, Tinkering.Site site, UUID inventor, String inventorName, long tick) {
		Device d = new Device(invention, site.at(invention.output()), site.base(), inventor, inventorName, tick);
		devices.add(d);
		setDirty();
	}

	/** Now and then: notices which devices went off, who's near enough to see one, and which are gone. */
	public void tick(ServerLevel world, long tick) {
		for (Iterator<Device> it = devices.iterator(); it.hasNext();) {
			Device d = it.next();
			if (!world.isLoaded(d.output)) {
				continue;
			}
			BlockState out = world.getBlockState(d.output);
			String block = BuiltInRegistries.BLOCK.getKey(out.getBlock()).toString();
			if (d.invention.partAt(d.invention.output()).map(p -> !p.block().equals(block)).orElse(true)
					&& !out.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) {
				it.remove(); // broken up: the part that did the work is gone
				setDirty();
				continue;
			}
			String state = out.toString();
			if (d.lastState != null && !d.lastState.equals(state)) {
				d.activations++;
				setDirty();
				if (!d.invention.selfRunning() && tick - d.lastHeard >= QUIET_TICKS && world.getEntity(d.inventor) instanceof AgentEntity owner
						&& owner.mind() != null && owner.blockPosition().distSqr(d.center) <= HEARS * HEARS
						&& owner.blockPosition().distSqr(d.center) > 4) {
					d.lastHeard = tick;
					owner.mind().perceive(tick, "My " + d.invention.name() + " went off: something set it off while I was away from it.",
							0.4, Set.of());
				}
			}
			d.lastState = state;
			if (tick % 100 == 0) {
				for (AgentEntity passer : world.getEntitiesOfClass(AgentEntity.class, new AABB(d.center).inflate(SEES))) {
					AgentMind mind = passer.mind();
					if (mind == null || mind.identity().id().equals(d.inventor)
							|| mind.inventions().stream().anyMatch(k -> k.invention().name().equalsIgnoreCase(d.invention.name()))) {
						continue;
					}
					mind.rememberInvention(new KnownInvention(d.invention, "saw", d.inventorName, d.inventor, tick, true,
							"it works; " + d.inventorName + " built it to " + Tinkering.lower(d.invention.purpose())));
					mind.perceive(tick, "I looked over " + d.inventorName + "'s " + d.invention.name() + ", a device to "
							+ Tinkering.lower(d.invention.purpose()) + ", and saw how it's made.", 0.45, Set.of(d.inventor));
				}
			}
		}
	}

	private CompoundTag toTag() {
		CompoundTag nbt = new CompoundTag();
		ListTag list = new ListTag();
		for (Device d : devices) {
			CompoundTag t = new CompoundTag();
			t.putString("invention", d.invention.toJson().toString());
			t.putLong("output", d.output.asLong());
			t.putLong("center", d.center.asLong());
			t.putString("inventor", d.inventor.toString());
			t.putString("inventorName", d.inventorName);
			t.putLong("built", d.built);
			t.putInt("activations", d.activations);
			list.add(t);
		}
		nbt.put("devices", list);
		return nbt;
	}

	private static Devices fromTag(CompoundTag nbt) {
		Devices all = new Devices();
		for (var element : nbt.getListOrEmpty("devices")) {
			if (!(element instanceof CompoundTag t)) {
				continue;
			}
			var parsed = Invention.parse(t.getStringOr("invention", ""));
			if (parsed.invention().isEmpty()) {
				continue;
			}
			try {
				Device d = new Device(parsed.invention().get(), BlockPos.of(t.getLongOr("output", 0)), BlockPos.of(t.getLongOr("center", 0)),
						UUID.fromString(t.getStringOr("inventor", "")), t.getStringOr("inventorName", ""), t.getLongOr("built", 0));
				d.activations = t.getIntOr("activations", 0);
				all.devices.add(d);
			} catch (IllegalArgumentException e) {
				// A garbled inventor id: drop it.
			}
		}
		return all;
	}
}
