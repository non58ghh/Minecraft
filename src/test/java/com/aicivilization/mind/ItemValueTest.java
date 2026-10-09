package com.aicivilization.mind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemValueTest {

	@Test
	void foodIsWorthMoreToTheHungry() {
		double hungry = ItemValue.unitValue(0.1, false, 0, "food", 5, 0, false);
		double fed = ItemValue.unitValue(0.9, false, 0, "food", 5, 0, false);
		assertTrue(hungry > 2 * fed);
	}

	@Test
	void plentyIsWorthLessPerPiece() {
		assertTrue(ItemValue.unitValue(0.5, false, 0, "food", 5, 0, false)
				> ItemValue.unitValue(0.5, false, 0, "food", 5, 20, false));
		assertTrue(ItemValue.unitValue(0.5, false, 0, "building", 0, 0, false)
				> ItemValue.unitValue(0.5, false, 0, "building", 0, 64, false));
	}

	@Test
	void woodMattersLessOnceHomeIsBuiltAndAMissingToolIsPrecious() {
		assertTrue(ItemValue.unitValue(0.5, false, 0, "building", 0, 0, false)
				> ItemValue.unitValue(0.5, true, 0, "building", 0, 0, false));
		assertTrue(ItemValue.unitValue(0.5, true, 0, "tool", 0, 0, true)
				> 10 * ItemValue.unitValue(0.5, true, 0, "tool", 0, 1, false));
	}

	@Test
	void aHungryAgentWouldTradeWoodForBread() {
		// Hungry and homeless: a loaf is worth more than several planks...
		double bread = ItemValue.unitValue(0.1, false, 0, "food", 5, 0, false);
		double plank = ItemValue.unitValue(0.1, false, 0, "building", 0, 30, false);
		assertTrue(bread > 4 * plank);
		// ...while a fed agent with a home would part with a loaf for enough wood.
		double breadFed = ItemValue.unitValue(0.9, true, 0, "food", 5, 6, false);
		double plankHome = ItemValue.unitValue(0.9, true, 0, "building", 0, 0, false);
		assertTrue(breadFed < 16 * plankHome);
	}
}
