package com.aicivilization.reasoning;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.KnownInvention;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Personality;
import com.aicivilization.mind.Possession;
import com.aicivilization.world.Discovery;
import com.aicivilization.world.RecipeCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an agent thinks up a device from: who it is, what has been going
 * wrong for it, the redstone parts it knows (how each behaves, never what to
 * build with them), and every device it has tried or seen, with what
 * actually happened. The model decides whether a device would help at all,
 * and if so designs one; the world then decides whether it works.
 */
public record InventBrief(String name, String temperament, List<String> condition, List<String> troubles, List<String> carrying,
		List<String> partsKnown, List<String> attempts, List<String> seen, String home) {

	/** How each part behaves, in the agent's terms. Keyed by block id; the item that makes it is noted where it differs. */
	static final Map<String, String> PART_RULES = rules();

	static final int MAX_TROUBLES = 15;
	static final int MAX_ATTEMPTS = 4;
	static final int MAX_SEEN = 3;

	public InventBrief {
		condition = List.copyOf(condition);
		troubles = List.copyOf(troubles);
		carrying = List.copyOf(carrying);
		partsKnown = List.copyOf(partsKnown);
		attempts = List.copyOf(attempts);
		seen = List.copyOf(seen);
	}

	/** From the agent's own mind: only parts it carries, knows how to make, or could work out from what it knows. */
	public static InventBrief of(AgentMind mind, RecipeCatalog catalog, long tick, String home) {
		Personality p = mind.personality();
		var n = mind.needs();
		Set<String> carried = new HashSet<>();
		List<String> carrying = new ArrayList<>();
		for (Possession item : mind.possessions()) {
			carried.add(item.itemId());
			carrying.add(item.quantity() + " " + item.itemId().replace("minecraft:", ""));
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, String> rule : PART_RULES.entrySet()) {
			String item = com.aicivilization.mind.Invention.PART_ITEMS.getOrDefault(rule.getKey(), rule.getKey());
			String how = carried.contains(item) ? "you have some"
					: mind.recipeBook().knows(item) ? "you know how to get it"
					: Discovery.workable(mind.recipeBook(), carried, item, catalog).isPresent() ? "you could work out how to make it"
					: null;
			if (how != null) {
				parts.add(rule.getKey().replace("minecraft:", "") + " (" + how + "): " + rule.getValue());
			}
		}
		List<String> troubles = mind.memories().all().stream()
				.sorted(Comparator.comparingDouble(MemoryEntry::importance).reversed())
				.limit(MAX_TROUBLES)
				.sorted(Comparator.comparingLong(MemoryEntry::tick))
				.map(m -> AgentContext.dayLabel(m.tick(), tick) + ": " + m.description())
				.toList();
		List<String> attempts = new ArrayList<>();
		List<String> seen = new ArrayList<>();
		for (KnownInvention k : mind.inventions()) {
			String line = k.invention().name() + " (to " + k.invention().purpose() + "): " + (k.works() ? "works" : "didn't work")
					+ "; " + k.observed() + ". As built: " + k.invention().toJson();
			if (k.how().equals("invented")) {
				attempts.add(line);
			} else {
				seen.add(k.inventor() + "'s " + line);
			}
		}
		return new InventBrief(mind.identity().name(),
				AgentContext.personalityWords(p.curiosity(), p.risk(), p.sociability(), p.ambition()),
				AgentContext.needLines(n.food(), n.safety(), n.social(), n.belonging(), 0, mind.home().isEmpty()),
				troubles, carrying, parts,
				attempts.subList(Math.max(0, attempts.size() - MAX_ATTEMPTS), attempts.size()),
				seen.subList(Math.max(0, seen.size() - MAX_SEEN), seen.size()), home);
	}

	static final String RULES = """
			You are one of the people of a small, young settlement in a Minecraft world. You can build things \
			from redstone. Decide whether a small redstone device would help you with something that has \
			actually been going wrong or getting in your way, and if so, design one.

			How redstone works here (the game's own rules; you don't know any ready-made designs, only the parts):
			- Power comes from sources: a lever (on until pulled again), a button (on for a moment), a pressure \
			plate (on while something stands on it), a tripwire (on while something walks through the string), a \
			redstone torch (on unless the block it's attached to is powered), a redstone block (always on).
			- A source powers the block it's attached to or standing on, and any dust right beside it.
			- Redstone dust carries power along the ground for up to 15 blocks. A line of dust powers the block it \
			runs straight into at its end, and the block it lies on.
			- A solid block that's powered (dust running into it, a lever on it) powers dust, pistons, doors, note \
			blocks and torches touching it. Glass doesn't carry power.
			- Things that act when powered: doors, trapdoors and fence gates open; a piston pushes the block in \
			front of it; a note block plays a note; a dispenser or dropper uses or throws out one item; a powered \
			rail speeds a cart.
			- Things need something to stand on or hang from: dust, plates, rails and torches need a block under \
			them; a lever or button needs a block behind it.

			Your device fits in a space x from -3 to 3, z from -3 to 3, y from 0 (the ground) to 3. Choose where:
			- "home": on clear ground near your home. x is east, z is south. Facings are north, south, east, west, \
			up, down.
			- "door": at your front door. 0,0,0 is the ground just outside the doorway; z counts steps further out \
			(nothing at z below 0: that's the doorway and the house); x is to your right as you stand in the doorway \
			looking out. Facings: "away" (out from the door), "toward_door", "left", "right", up, down. Only things \
			that can be walked over (a plate, dust, a rail, tripwire string) can go at 0,0,0; the house wall beside \
			the door, at x -1 and 1, z -1, can hold a lever, button or torch facing away.

			Block state uses the game's names, as "key=value" pairs: a lever or button has face (floor, wall or \
			ceiling) and facing (for a wall one, pointing away from the block it hangs on); a piston, dispenser \
			dropper has facing (the way it pushes or throws); a repeater has facing (the side its \
			power comes in from) and delay (1 to 4); a redstone_wall_torch has facing (away from its block); doors \
			have facing (the way they face when shut) and hinge (left or right).

			Rules:
			- Only design for a real problem from what you remember, or to improve a device you've tried or seen. If \
			nothing calls for a device, say so: most of the time nothing does.
			- Use only the parts listed as yours to use, and as few as will do.
			- Learn from what happened to your earlier attempts: they show exactly what you built and what it did.
			- The trigger is the part someone pulls, presses, steps on or walks through to try it (leave it out \
			only if it runs by itself). The output is the part that should respond.

			Reply with JSON only, no other text. Either {"invent": false} or:
			{"invent": true, "problem": "<what's wrong, in your words>", "name": "<a short name for it>", \
			"purpose": "<to ...>", "category": "night_defence" | "visitors" | "farming" | "storage" | "travel" | \
			"curiosity", "anchor": "home" | "door", "selfRunning": false, \
			"parts": [{"x": 0, "y": 0, "z": 0, "block": "<block name>", "state": "<key=value,key=value or empty>"}], \
			"trigger": {"x": 0, "y": 0, "z": 0}, "output": {"x": 0, "y": 0, "z": 0}, \
			"expect": "<what should happen when it's set off>"}
			""";

	private String particulars() {
		return "You are " + name + ". " + temperament + "\n\nHow you are now:\n- " + String.join("\n- ", condition)
				+ "\n\nYour home: " + home
				+ "\n\nWhat you remember most (oldest first):\n" + list(troubles, "Nothing much yet.")
				+ "\nYou carry: " + (carrying.isEmpty() ? "nothing" : String.join(", ", carrying)) + ".\n"
				+ "\nParts yours to use:\n" + list(partsKnown, "None yet.")
				+ "\nYour earlier attempts:\n" + list(attempts, "None yet.")
				+ "\nDevices you've seen others build:\n" + list(seen, "None.");
	}

	private static String list(List<String> lines, String none) {
		return (lines.isEmpty() ? "- " + none : "- " + String.join("\n- ", lines)) + "\n";
	}

	public String toPrompt() {
		return RULES + "\n" + particulars();
	}

	public PromptParts parts() {
		return new PromptParts(RULES, particulars());
	}

	private static Map<String, String> rules() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("minecraft:redstone_wire", "redstone dust (made from redstone): carries power along the ground.");
		m.put("minecraft:redstone_torch", "a power source standing on top of a block; off while that block is powered.");
		m.put("minecraft:redstone_wall_torch", "the same torch on the side of a block (made from a redstone torch).");
		m.put("minecraft:repeater", "passes power one way, from its facing side out the other, after a delay; restores full strength.");
		m.put("minecraft:lever", "a switch: on when pulled, off when pulled again.");
		m.put("minecraft:stone_button", "on for about a second when pressed.");
		m.put("minecraft:oak_button", "on for a second and a half when pressed.");
		m.put("minecraft:stone_pressure_plate", "on while a person, animal or monster stands on it.");
		m.put("minecraft:oak_pressure_plate", "on while anything, even a dropped item, is on it.");
		m.put("minecraft:tripwire_hook", "two hooks on walls facing each other, with string between them in a straight line.");
		m.put("minecraft:tripwire", "string (made from string) laid between two hooks; walking through it powers both hooks.");
		m.put("minecraft:piston", "pushes the block in front of it out one block while powered.");
		m.put("minecraft:sticky_piston", "like a piston, and pulls the block back when power stops.");
		m.put("minecraft:note_block", "plays a note each time it's powered.");
		m.put("minecraft:oak_door", "opens while powered.");
		m.put("minecraft:iron_door", "opens only while powered; nothing without hands can open it.");
		m.put("minecraft:oak_trapdoor", "a hatch; opens while powered.");
		m.put("minecraft:iron_trapdoor", "opens only while powered.");
		m.put("minecraft:oak_fence_gate", "a gate in a fence; opens while powered.");
		m.put("minecraft:hopper", "moves items from a chest or furnace above into the one it faces; stops while powered.");
		m.put("minecraft:dropper", "throws out one item from inside each time it's powered.");
		m.put("minecraft:dispenser", "uses one item from inside each time it's powered: fires an arrow, pours water.");
		m.put("minecraft:rail", "track for a minecart.");
		m.put("minecraft:powered_rail", "track that speeds a cart along while powered.");
		m.put("minecraft:detector_rail", "track that gives power while a cart is on it.");
		m.put("minecraft:target", "a block that gives power when hit by an arrow.");
		m.put("minecraft:redstone_block", "always gives power to everything touching it; a piston can move it.");
		m.put("minecraft:cobblestone", "a plain block to stand things on and carry power through.");
		m.put("minecraft:stone", "a plain block.");
		m.put("minecraft:oak_planks", "a plain block (any planks).");
		m.put("minecraft:dirt", "a plain block.");
		m.put("minecraft:glass", "a block you can see through; doesn't carry power.");
		return m;
	}
}
