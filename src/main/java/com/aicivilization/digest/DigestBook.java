package com.aicivilization.digest;

import com.aicivilization.events.SimEvent;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A day-by-day account of each agent, for whoever is watching (never read by
 * agents): how far it went, how long it stood still, what it ate and where
 * its food came from, who it met, and how it died if it did. Built from the
 * body's position, sampled as the observer runs, and from the event log.
 * Also raises alerts worth a notification: someone starving, someone who
 * hasn't moved in a day (or left home in three), a death.
 *
 * <p>Plain Java, no world access: the observer feeds it.
 */
public final class DigestBook {

	static final long DAY = 24000;
	/** Days of account kept per agent. */
	static final int KEEP_DAYS = 7;
	/** Moving less than this from where it last stood still counts as standing still. */
	static final double STILL_RADIUS = 3.0;
	static final long STUCK_AFTER = DAY;
	static final long HOMEBOUND_AFTER = 3 * DAY;
	static final double STARVING_FOOD = 0.1;
	/** A death stays an alert this long, so a check that runs later still sees it. */
	static final long DEATH_ALERT_TICKS = 3 * DAY;

	/** One agent's day. */
	public static final class Day {
		long day;
		double distance;
		int startX, startZ, endX, endZ;
		double farthest;
		long longestStill;
		float minHealth = -1;
		int talks;
		String died;
		/** Whether the day's starting point is known yet (an event can come before the first position). */
		boolean placed;
		final Map<String, Integer> ate = new TreeMap<>();
		final Map<String, Integer> got = new TreeMap<>();
		final TreeSet<String> met = new TreeSet<>();
		final Map<String, Double> needs = new TreeMap<>();
	}

	static final class Alert {
		String kind;
		String text;
		long raisedTick;
		long raisedAtMillis;
	}

	static final class Tracker {
		UUID id;
		String name;
		boolean hasLast;
		double lastX, lastZ;
		double anchorX, anchorZ;
		long anchorTick;
		boolean atHome;
		boolean alive = true;
		double food = 1;
		float health = -1;
		final LinkedHashMap<Long, Day> days = new LinkedHashMap<>();
		final Map<String, Alert> alerts = new LinkedHashMap<>();
	}

	private final Map<UUID, Tracker> trackers = new LinkedHashMap<>();
	/** The last event taken in, so a restart doesn't count events twice. */
	private long lastEventId;

	public long lastEventId() {
		return lastEventId;
	}

	private Tracker tracker(UUID id, String name) {
		Tracker t = trackers.computeIfAbsent(id, k -> {
			Tracker n = new Tracker();
			n.id = k;
			return n;
		});
		if (name != null) {
			t.name = name;
		}
		return t;
	}

	private Day day(Tracker t, long tick) {
		long d = tick / DAY;
		Day day = t.days.computeIfAbsent(d, k -> {
			Day n = new Day();
			n.day = k;
			if (t.hasLast) {
				n.startX = (int) Math.round(t.lastX);
				n.startZ = (int) Math.round(t.lastZ);
				n.placed = true;
			}
			return n;
		});
		while (t.days.size() > KEEP_DAYS) {
			t.days.remove(t.days.keySet().iterator().next());
		}
		return day;
	}

	/** Where a living agent's body is now, and how it is. Called every second or so. */
	public void sample(UUID id, String name, long tick, double x, double z, float health, double food, double safety,
			double social, double belonging, boolean atHome, long nowMillis) {
		Tracker t = tracker(id, name);
		if (!t.hasLast) {
			t.hasLast = true;
			t.lastX = x;
			t.lastZ = z;
			t.anchorX = x;
			t.anchorZ = z;
			t.anchorTick = tick;
		}
		Day day = day(t, tick);
		if (!day.placed) {
			day.startX = (int) Math.round(x);
			day.startZ = (int) Math.round(z);
			day.placed = true;
		}
		double step = Math.hypot(x - t.lastX, z - t.lastZ);
		if (step < 64) {
			// A big jump is a teleport (climbing free, a restart), not a walk.
			day.distance += step;
		}
		t.lastX = x;
		t.lastZ = z;
		if (Math.hypot(x - t.anchorX, z - t.anchorZ) > STILL_RADIUS) {
			t.anchorX = x;
			t.anchorZ = z;
			t.anchorTick = tick;
		}
		day.longestStill = Math.max(day.longestStill, tick - t.anchorTick);
		day.endX = (int) Math.round(x);
		day.endZ = (int) Math.round(z);
		day.farthest = Math.max(day.farthest, Math.hypot(x - day.startX, z - day.startZ));
		if (health >= 0) {
			day.minHealth = day.minHealth < 0 ? health : Math.min(day.minHealth, health);
		}
		day.needs.put("food", round(food));
		day.needs.put("safety", round(safety));
		day.needs.put("social", round(social));
		day.needs.put("belonging", round(belonging));
		t.atHome = atHome;
		t.food = food;
		t.health = health;
		t.alive = true;
		updateAlerts(t, tick, nowMillis);
	}

	/** How long it has stood within a few blocks of one spot. */
	public long stillFor(UUID id, long tick) {
		Tracker t = trackers.get(id);
		return t == null || !t.hasLast ? 0 : tick - t.anchorTick;
	}

	private static final Pattern ATE = Pattern.compile("^ ate some (.+)\\.$");
	private static final Pattern HARVESTED = Pattern.compile("^ harvested (?:(\\d+) plants of )?(.+?)\\.$");
	private static final Pattern HUNTED = Pattern.compile("^ hunted an? (.+?)\\.$");
	private static final Pattern GAVE = Pattern.compile("^ gave (\\S+) (\\d+) (.+?), as they'd agreed\\.$");
	private static final Pattern SHARED = Pattern.compile("^ saw (\\S+) was hungry and gave them (\\d+) (.+?)\\.$");
	private static final Pattern TRADED = Pattern.compile("^ traded (\\d+) (.+?) to (\\S+) for (\\d+) (.+?)\\.$");

	/** Takes in one event from the log. {@code names} maps the event's subjects to names. */
	public void event(SimEvent e, java.util.function.Function<UUID, String> names, long nowMillis) {
		lastEventId = Math.max(lastEventId, e.id());
		if (e.subjects().isEmpty()) {
			return;
		}
		UUID actorId = e.subjects().get(0);
		Tracker actor = tracker(actorId, names.apply(actorId));
		String name = actor.name == null ? "" : actor.name;
		String rest = e.summary().startsWith(name) ? e.summary().substring(name.length()) : e.summary();
		Day day = day(actor, e.tick());
		switch (e.type()) {
			case DEATH -> {
				day.died = e.summary();
				actor.alive = false;
				actor.alerts.clear();
				raise(actor, "death", e.summary(), e.tick(), nowMillis);
			}
			case CONVERSATION -> {
				for (UUID other : e.subjects()) {
					Tracker t = tracker(other, names.apply(other));
					Day d = day(t, e.tick());
					d.talks++;
					for (UUID o : e.subjects()) {
						if (!o.equals(other)) {
							String n = names.apply(o);
							if (n != null) {
								d.met.add(n);
							}
						}
					}
				}
				Matcher m = SHARED.matcher(rest);
				if (m.matches()) {
					received(names, e, m.group(1), Integer.parseInt(m.group(2)), m.group(3), "shared by " + name);
				}
				m = TRADED.matcher(rest);
				if (m.matches()) {
					add(day.got, m.group(5) + " (traded)", Integer.parseInt(m.group(4)));
					received(names, e, m.group(3), Integer.parseInt(m.group(1)), m.group(2) + " (traded)", null);
				}
			}
			case ACTION -> {
				Matcher m = ATE.matcher(rest);
				if (m.matches()) {
					add(day.ate, m.group(1), 1);
					return;
				}
				m = HARVESTED.matcher(rest);
				if (m.matches()) {
					add(day.got, m.group(2) + " (harvested)", m.group(1) == null ? 1 : Integer.parseInt(m.group(1)));
					return;
				}
				m = HUNTED.matcher(rest);
				if (m.matches()) {
					add(day.got, m.group(1) + " (hunted)", 1);
					return;
				}
				m = GAVE.matcher(rest);
				if (m.matches()) {
					received(names, e, m.group(1), Integer.parseInt(m.group(2)), m.group(3), "given by " + name);
				}
			}
			default -> {
			}
		}
	}

	/** The named other subject of {@code e} got some of something. */
	private void received(java.util.function.Function<UUID, String> names, SimEvent e, String whoName, int count, String what,
			String from) {
		for (UUID id : e.subjects()) {
			if (whoName.equals(names.apply(id))) {
				Day d = day(tracker(id, whoName), e.tick());
				add(d.got, from == null ? what : what + " (" + from + ")", count);
			}
		}
	}

	private void updateAlerts(Tracker t, long tick, long nowMillis) {
		long still = tick - t.anchorTick;
		set(t, "starving", t.alive && t.food < STARVING_FOOD,
				t.name + " is starving" + (t.health >= 0 ? " (" + Math.round(t.health) + "/20 health)" : "") + ".", tick, nowMillis);
		set(t, "stuck", t.alive && !t.atHome && still > STUCK_AFTER,
				t.name + " hasn't moved from one spot in a day.", tick, nowMillis);
		set(t, "homebound", t.alive && t.atHome && still > HOMEBOUND_AFTER,
				t.name + " hasn't left home in three days.", tick, nowMillis);
		t.alerts.entrySet().removeIf(a -> a.getKey().equals("death") && tick - a.getValue().raisedTick > DEATH_ALERT_TICKS);
	}

	private void set(Tracker t, String kind, boolean on, String text, long tick, long nowMillis) {
		if (!on) {
			t.alerts.remove(kind);
		} else if (!t.alerts.containsKey(kind)) {
			raise(t, kind, text, tick, nowMillis);
		}
	}

	private void raise(Tracker t, String kind, String text, long tick, long nowMillis) {
		Alert a = new Alert();
		a.kind = kind;
		a.text = text;
		a.raisedTick = tick;
		a.raisedAtMillis = nowMillis;
		t.alerts.put(kind, a);
	}

	/**
	 * Alerts standing at {@code tick}: each with when it was first raised
	 * (game tick and wall clock). A death stands for three days after it
	 * happened, whenever it was taken in.
	 */
	public JsonObject alertsJson(long tick) {
		JsonArray list = new JsonArray();
		for (Tracker t : trackers.values()) {
			for (Alert a : t.alerts.values()) {
				if (a.kind.equals("death") && tick - a.raisedTick > DEATH_ALERT_TICKS) {
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("kind", a.kind);
				o.addProperty("agent", t.name);
				o.addProperty("text", a.text);
				o.addProperty("raisedTick", a.raisedTick);
				o.addProperty("raisedAtMillis", a.raisedAtMillis);
				list.add(o);
			}
		}
		JsonObject out = new JsonObject();
		out.add("alerts", list);
		return out;
	}

	/** The last few days of every agent, newest day first. */
	public JsonObject toJson() {
		JsonArray agents = new JsonArray();
		for (Tracker t : trackers.values()) {
			JsonObject a = new JsonObject();
			a.addProperty("id", t.id.toString());
			a.addProperty("name", t.name);
			a.addProperty("alive", t.alive);
			JsonArray days = new JsonArray();
			List<Day> list = new ArrayList<>(t.days.values());
			for (int i = list.size() - 1; i >= 0; i--) {
				days.add(GSON.toJsonTree(list.get(i)));
			}
			a.add("days", days);
			agents.add(a);
		}
		JsonObject out = new JsonObject();
		out.add("agents", agents);
		return out;
	}

	private static final Gson GSON = new Gson();

	/** For saving with the world. */
	public String save() {
		return GSON.toJson(new Saved(new ArrayList<>(trackers.values()), lastEventId));
	}

	public static DigestBook load(String json) {
		DigestBook book = new DigestBook();
		if (json == null || json.isEmpty()) {
			return book;
		}
		try {
			Saved saved = GSON.fromJson(json, Saved.class);
			if (saved != null) {
				book.lastEventId = saved.lastEventId;
			}
			if (saved != null && saved.trackers != null) {
				for (Tracker t : saved.trackers) {
					if (t.id != null) {
						book.trackers.put(t.id, t);
					}
				}
			}
		} catch (RuntimeException e) {
			// An unreadable account starts afresh; it's only for watching.
		}
		return book;
	}

	private record Saved(List<Tracker> trackers, long lastEventId) {
	}

	private static void add(Map<String, Integer> map, String key, int n) {
		map.merge(key, n, Integer::sum);
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}
}
