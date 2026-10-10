package com.aicivilization.mind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The places an agent knows from its own experience: its fields, stands of
 * trees, saplings it's watching, where it saw animals, water, spots where it
 * got stuck, and homes it has seen. Part of the mind and saved with it, so a
 * farmer still knows where its field is after the server restarts. Places
 * fade: one not seen again for long enough is forgotten, sooner for things
 * that move (animals) than for things that don't (a field, water).
 *
 * <p>Plain block coordinates only: the embodiment notes what it perceived;
 * nothing here looks at the world.
 */
public final class Places {

	public enum Kind {
		/** A field it planted. */
		FIELD(8, 4, 10 * Places.DAY),
		/** A stand of trees it saw. */
		WOODS(12, 12, 5 * Places.DAY),
		/** A sapling it noticed (or set in the ground itself) and is watching. */
		SAPLING(8, 0, 5 * Places.DAY),
		/** Where it saw animals worth hunting. */
		ANIMALS(8, 16, Places.DAY),
		/** Water it came across. */
		WATER(6, 16, 10 * Places.DAY),
		/** A spot it got stuck in. */
		STUCK(16, 2, 2 * Places.DAY),
		/** Someone's home it has seen; {@link Place#about()} is whose. */
		HOME(12, 4, 20 * Places.DAY);

		final int cap;
		final int sameWithin;
		final long fadeTicks;

		Kind(int cap, int sameWithin, long fadeTicks) {
			this.cap = cap;
			this.sameWithin = sameWithin;
			this.fadeTicks = fadeTicks;
		}
	}

	static final long DAY = 24000;

	/**
	 * A remembered place. {@code about} is whose it is (homes), or null;
	 * {@code mine} marks one it made itself (a sapling it planted).
	 */
	public record Place(Kind kind, int x, int y, int z, long tick, UUID about, boolean mine) {
		public long distSqr(int px, int py, int pz) {
			long dx = x - px, dy = y - py, dz = z - pz;
			return dx * dx + dy * dy + dz * dz;
		}
	}

	private final Map<Kind, List<Place>> places = new EnumMap<>(Kind.class);

	/**
	 * Notes a place seen (or seen again) now: refreshes the same place if
	 * already known (close enough, and for homes the same owner), else adds
	 * it, forgetting the oldest of its kind past the cap.
	 */
	public void note(Kind kind, int x, int y, int z, long tick, UUID about, boolean mine) {
		List<Place> list = places.computeIfAbsent(kind, k -> new ArrayList<>());
		long same = (long) kind.sameWithin * kind.sameWithin;
		for (int i = 0; i < list.size(); i++) {
			Place p = list.get(i);
			boolean samePlace = kind == Kind.HOME && about != null ? about.equals(p.about()) : p.distSqr(x, y, z) <= same;
			if (samePlace) {
				list.set(i, new Place(kind, x, y, z, tick, about, mine || p.mine()));
				return;
			}
		}
		list.add(new Place(kind, x, y, z, tick, about, mine));
		while (list.size() > kind.cap) {
			list.remove(list.stream().min(Comparator.comparingLong(Place::tick)).orElseThrow());
		}
	}

	public void note(Kind kind, int x, int y, int z, long tick) {
		note(kind, x, y, z, tick, null, false);
	}

	/** Forgets places of this kind within {@code radius} of the given spot (it went and they weren't there). */
	public void forget(Kind kind, int x, int y, int z, int radius) {
		List<Place> list = places.get(kind);
		if (list != null) {
			long r = (long) radius * radius;
			list.removeIf(p -> p.distSqr(x, y, z) <= r);
		}
	}

	/** Forgets every place of this kind (it has no more use for them). */
	public void forgetAll(Kind kind) {
		places.remove(kind);
	}

	/** Places of this kind it still remembers at {@code tick}, newest first. */
	public List<Place> of(Kind kind, long tick) {
		List<Place> list = places.getOrDefault(kind, List.of());
		return list.stream().filter(p -> tick - p.tick() <= kind.fadeTicks)
				.sorted(Comparator.comparingLong(Place::tick).reversed()).toList();
	}

	public Optional<Place> nearest(Kind kind, int x, int y, int z, long tick) {
		return of(kind, tick).stream().min(Comparator.comparingLong(p -> p.distSqr(x, y, z)));
	}

	public boolean knows(Kind kind, long tick) {
		return !of(kind, tick).isEmpty();
	}

	/** Drops faded places; done now and then so the lists don't hold the long forgotten. */
	public void fade(long tick) {
		places.forEach((kind, list) -> list.removeIf(p -> tick - p.tick() > kind.fadeTicks));
	}

	/** Everything remembered, for saving. */
	public List<Place> all() {
		List<Place> out = new ArrayList<>();
		places.values().forEach(out::addAll);
		return out;
	}

	/** Restores a saved place as it was. */
	public void restore(Place p) {
		places.computeIfAbsent(p.kind(), k -> new ArrayList<>()).add(p);
	}
}
