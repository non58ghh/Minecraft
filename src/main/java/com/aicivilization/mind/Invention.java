package com.aicivilization.mind;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A redstone device an agent has thought up: what it's for, the parts and
 * where each goes, which part sets it off and which part should respond.
 * Nothing here says whether it works; only building it and trying it in the
 * world can tell (see {@code action.Tinkering}).
 *
 * <p>Positions are relative to the build site: x and z from -3 to 3, y from
 * 0 (ground level) to 3. Each part may give block state, the way the game
 * names it ({@code "facing": "north"}, {@code "face": "wall"}).
 */
public record Invention(String name, String purpose, List<Part> parts, Cell trigger, Cell output, String expect) {

	public static final int REACH = 3;
	public static final int HEIGHT = 3;
	public static final int MAX_PARTS = 40;

	/** What can go into an invention: the redstone parts, and plain blocks to hold them up. Nothing from the Nether. */
	public static final Map<String, String> PART_ITEMS = partItems();
	/** Parts that can set a device off: someone presses, pulls or steps on them. */
	public static final Set<String> TRIGGERS = Set.of("minecraft:lever", "minecraft:stone_button", "minecraft:oak_button",
			"minecraft:stone_pressure_plate", "minecraft:oak_pressure_plate");

	public record Cell(int x, int y, int z) {
		public String toString() {
			return x + "," + y + "," + z;
		}
	}

	/** One block of the device: which block, where, and in what state. */
	public record Part(Cell at, String block, Map<String, String> state) {
		public Part {
			state = Map.copyOf(state);
		}

		/** The item it takes to put this part down. */
		public String item() {
			return PART_ITEMS.getOrDefault(block, block);
		}
	}

	public Invention {
		parts = List.copyOf(parts);
	}

	/** The part at this cell, if any. */
	public Optional<Part> partAt(Cell cell) {
		return parts.stream().filter(p -> p.at().equals(cell)).findFirst();
	}

	/** Items it takes to build, as item id to count. */
	public Map<String, Integer> materials() {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Part p : parts) {
			out.merge(p.item(), 1, Integer::sum);
		}
		return out;
	}

	/** The outcome of reading one: the invention, or why it doesn't make sense. */
	public record Parsed(Optional<Invention> invention, String problem) {
		static Parsed ok(Invention i) {
			return new Parsed(Optional.of(i), "");
		}

		static Parsed bad(String problem) {
			return new Parsed(Optional.empty(), problem);
		}
	}

	/**
	 * Reads one from JSON: {"name", "purpose", "expect", "parts": [{"x","y","z","block","state"?}],
	 * "trigger": {"x","y","z"}, "output": {"x","y","z"}}. Block names may leave off "minecraft:".
	 */
	public static Parsed parse(String json) {
		try {
			return parse(JsonParser.parseString(json).getAsJsonObject());
		} catch (RuntimeException e) {
			return Parsed.bad("not readable: " + e.getMessage());
		}
	}

	public static Parsed parse(JsonObject o) {
		String name = str(o, "name");
		String purpose = str(o, "purpose");
		if (name.isBlank() || purpose.isBlank()) {
			return Parsed.bad("it needs a name and a purpose");
		}
		if (!o.has("parts") || !o.get("parts").isJsonArray()) {
			return Parsed.bad("it has no parts");
		}
		List<Part> parts = new ArrayList<>();
		Set<Cell> taken = new HashSet<>();
		for (JsonElement e : o.getAsJsonArray("parts")) {
			JsonObject p = e.getAsJsonObject();
			Cell at = cell(p);
			String block = id(str(p, "block"));
			if (!PART_ITEMS.containsKey(block) && !block.endsWith("_planks")) {
				return Parsed.bad(block + " isn't something that can go in a device");
			}
			if (Math.abs(at.x()) > REACH || Math.abs(at.z()) > REACH || at.y() < 0 || at.y() > HEIGHT) {
				return Parsed.bad("a part at " + at + " is outside the space");
			}
			if (!taken.add(at)) {
				return Parsed.bad("two parts at " + at);
			}
			Map<String, String> state = new LinkedHashMap<>();
			if (p.has("state") && p.get("state").isJsonObject()) {
				for (Map.Entry<String, JsonElement> s : p.getAsJsonObject("state").entrySet()) {
					state.put(s.getKey().toLowerCase(Locale.ROOT), s.getValue().getAsString().toLowerCase(Locale.ROOT));
				}
			}
			parts.add(new Part(at, block, state));
		}
		if (parts.isEmpty() || parts.size() > MAX_PARTS) {
			return Parsed.bad("it needs between 1 and " + MAX_PARTS + " parts");
		}
		if (!o.has("trigger") || !o.has("output")) {
			return Parsed.bad("it needs a trigger and an output");
		}
		Cell trigger = cell(o.getAsJsonObject("trigger"));
		Cell output = cell(o.getAsJsonObject("output"));
		Invention inv = new Invention(name.strip(), purpose.strip(), parts, trigger, output, str(o, "expect").strip());
		Optional<Part> t = inv.partAt(trigger);
		if (t.isEmpty() || !TRIGGERS.contains(t.get().block())) {
			return Parsed.bad("the trigger must be a lever, button or pressure plate in the device");
		}
		if (inv.partAt(output).isEmpty() || output.equals(trigger)) {
			return Parsed.bad("the output must be another part of the device");
		}
		return Parsed.ok(inv);
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("purpose", purpose);
		o.addProperty("expect", expect);
		JsonArray arr = new JsonArray();
		for (Part p : parts) {
			JsonObject j = cellJson(p.at());
			j.addProperty("block", p.block());
			if (!p.state().isEmpty()) {
				JsonObject s = new JsonObject();
				p.state().forEach(s::addProperty);
				j.add("state", s);
			}
			arr.add(j);
		}
		o.add("parts", arr);
		o.add("trigger", cellJson(trigger));
		o.add("output", cellJson(output));
		return o;
	}

	private static JsonObject cellJson(Cell c) {
		JsonObject j = new JsonObject();
		j.addProperty("x", c.x());
		j.addProperty("y", c.y());
		j.addProperty("z", c.z());
		return j;
	}

	private static Cell cell(JsonObject o) {
		return new Cell(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
	}

	private static String id(String block) {
		String b = block.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
		return b.contains(":") ? b : "minecraft:" + b;
	}

	private static Map<String, String> partItems() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("minecraft:redstone_wire", "minecraft:redstone");
		for (String b : List.of("redstone_torch", "repeater", "lever", "stone_button", "oak_button", "stone_pressure_plate",
				"oak_pressure_plate", "piston", "sticky_piston", "note_block", "oak_door", "iron_door", "oak_trapdoor",
				"iron_trapdoor", "oak_fence_gate", "hopper", "dropper", "dispenser", "rail", "powered_rail", "detector_rail",
				"activator_rail", "target", "redstone_block", "tripwire_hook",
				"cobblestone", "stone", "dirt", "oak_log", "glass", "sand")) {
			m.put("minecraft:" + b, "minecraft:" + b);
		}
		return Map.copyOf(m);
	}
}
