package com.aicivilization.observer;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainMapTest {

	@Test
	void aSmallSettlementIsDrawnBlockForBlockWithAMargin() {
		TerrainMap.Bounds b = TerrainMap.bounds(List.of(new double[] {-100.4, 50.2}, new double[] {-80.7, 30.9}));
		assertEquals(-101 - TerrainMap.MARGIN, b.x0());
		assertEquals(30 - TerrainMap.MARGIN, b.z0());
		assertEquals(1, b.step());
		assertTrue(b.x0() + b.w() * b.step() > -80 + TerrainMap.MARGIN);
		assertTrue(b.z0() + b.h() * b.step() > 51 + TerrainMap.MARGIN);
	}

	@Test
	void aSpreadOutOneUsesBiggerCellsAndStaysWithinTheCap() {
		TerrainMap.Bounds b = TerrainMap.bounds(List.of(new double[] {-172, 35}, new double[] {124, -117}));
		assertTrue(b.step() > 1);
		assertTrue(b.w() <= TerrainMap.MAX_CELLS && b.h() <= TerrainMap.MAX_CELLS);
		assertTrue(b.x0() + b.w() * b.step() >= 124 + TerrainMap.MARGIN);
	}
}
