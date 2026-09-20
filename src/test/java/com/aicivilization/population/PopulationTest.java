package com.aicivilization.population;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopulationTest {

	@Test
	void mindOutlivesRecordedDeath() {
		Population population = new Population();
		UUID id = UUID.randomUUID();
		AgentMind mind = new AgentMind(new Identity(id, "Elias", 0), new Personality(0.5, 0.5, 0.5, 0.5), Needs.initial());
		population.add(mind);

		population.recordDeath(id);

		assertTrue(population.getMind(id).isPresent(), "the mind must still exist in the population after death");
		assertFalse(population.getMind(id).orElseThrow().isAlive());
	}
}
