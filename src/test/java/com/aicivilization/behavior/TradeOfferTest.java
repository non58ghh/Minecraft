package com.aicivilization.behavior;

import com.aicivilization.mind.Possession;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeOfferTest {

	@Test
	void anAgentNamesOnlyItsLeastValuedThingsAndNeverWhatItKeepsBack() {
		List<Possession> pack = List.of(new Possession("minecraft:stone_pickaxe", 1, 0),
				new Possession("minecraft:oak_log", 5, 0), new Possession("minecraft:cobblestone", 5, 0),
				new Possession("minecraft:wheat_seeds", 5, 0), new Possession("minecraft:dirt", 5, 0),
				new Possession("minecraft:stick", 5, 0), new Possession("minecraft:bread", 0, 0));
		Map<String, Double> values = Map.of("minecraft:stone_pickaxe", 0.01, "minecraft:oak_log", 0.5,
				"minecraft:cobblestone", 0.2, "minecraft:wheat_seeds", 0.1, "minecraft:dirt", 0.05,
				"minecraft:stick", 0.3, "minecraft:bread", 0.0);
		List<String> offered = TradeBehavior.pickOffers(pack, "minecraft:stone_pickaxe"::equals, values::get)
				.stream().map(Possession::itemId).toList();
		// Cheapest first, at most four, the kept-back tool and the empty stack left out.
		assertEquals(List.of("minecraft:dirt", "minecraft:wheat_seeds", "minecraft:cobblestone", "minecraft:stick"),
				offered);
	}
}
