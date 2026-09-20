package com.aicivilization.events;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimEventTest {

	@Test
	void causesAreCapturedDefensivelyAndTyped() {
		List<Cause> causes = new ArrayList<>();
		causes.add(Cause.needState("food", 0.1));
		SimEvent event = new SimEvent(1, 100, EventType.DECISION, List.of(UUID.randomUUID()), "decided", causes);

		causes.add(Cause.event(2, "later addition"));

		assertEquals(1, event.causes().size(), "SimEvent must defensively copy the causes list at construction");
		assertEquals(CauseType.NEED_STATE, event.causes().get(0).sourceType());
		assertThrows(UnsupportedOperationException.class, () -> event.causes().add(Cause.event(3, "immutable")));
	}
}
