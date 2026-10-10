package com.aicivilization.digest;

import com.aicivilization.events.EventType;
import com.aicivilization.events.SimEvent;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DigestBookTest {

	private static final UUID WILDER = UUID.randomUUID();
	private static final UUID MABRY = UUID.randomUUID();
	private static final Map<UUID, String> NAMES = Map.of(WILDER, "Wilder", MABRY, "Mabry");
	private long id = 1;

	private SimEvent ev(long tick, EventType type, String summary, UUID... who) {
		return new SimEvent(id++, tick, type, List.of(who), summary, List.of());
	}

	private void sample(DigestBook b, long tick, double x, double food, boolean home) {
		b.sample(WILDER, "Wilder", tick, x, 0, 18f, food, 1, 0.5, 0.5, home, tick);
	}

	@Test
	void aDayRecordsDistanceFoodAndCompany() {
		DigestBook b = new DigestBook();
		sample(b, 0, 0, 0.8, false);
		sample(b, 100, 10, 0.8, false);
		sample(b, 200, 30, 0.8, false);
		b.event(ev(150, EventType.ACTION, "Wilder ate some bread.", WILDER), NAMES::get, 0);
		b.event(ev(160, EventType.ACTION, "Wilder harvested 3 plants of wheat.", WILDER), NAMES::get, 0);
		b.event(ev(170, EventType.ACTION, "Wilder hunted a cow.", WILDER), NAMES::get, 0);
		b.event(ev(180, EventType.CONVERSATION, "Wilder and Mabry talked for a while.", WILDER, MABRY), NAMES::get, 0);
		b.event(ev(190, EventType.ACTION, "Mabry gave Wilder 4 bread, as they'd agreed.", MABRY, WILDER), NAMES::get, 0);
		JsonObject day = wilder(b).getAsJsonArray("days").get(0).getAsJsonObject();
		assertEquals(30.0, day.get("distance").getAsDouble(), 1e-9);
		assertEquals(1, day.getAsJsonObject("ate").get("bread").getAsInt());
		assertEquals(3, day.getAsJsonObject("got").get("wheat (harvested)").getAsInt());
		assertEquals(1, day.getAsJsonObject("got").get("cow (hunted)").getAsInt());
		assertEquals(4, day.getAsJsonObject("got").get("bread (given by Mabry)").getAsInt());
		assertEquals("Mabry", day.getAsJsonArray("met").get(0).getAsString());
	}

	private static JsonObject wilder(DigestBook b) {
		for (var a : b.toJson().getAsJsonArray("agents")) {
			if ("Wilder".equals(a.getAsJsonObject().get("name").getAsString())) {
				return a.getAsJsonObject();
			}
		}
		throw new AssertionError("no Wilder");
	}

	@Test
	void standingStillAwayFromHomeForADayRaisesAnAlertOnce() {
		DigestBook b = new DigestBook();
		sample(b, 0, 0, 0.8, false);
		sample(b, DigestBook.DAY / 2, 1, 0.8, false);
		assertEquals(0, b.alertsJson(DigestBook.DAY * 2).getAsJsonArray("alerts").size());
		sample(b, DigestBook.DAY + 10, 1, 0.8, false);
		var alerts = b.alertsJson(DigestBook.DAY * 2).getAsJsonArray("alerts");
		assertEquals(1, alerts.size());
		assertEquals("stuck", alerts.get(0).getAsJsonObject().get("kind").getAsString());
		assertEquals(DigestBook.DAY + 10, alerts.get(0).getAsJsonObject().get("raisedTick").getAsLong());
		sample(b, DigestBook.DAY + 500, 1, 0.8, false);
		assertEquals(DigestBook.DAY + 10,
				b.alertsJson(DigestBook.DAY * 2).getAsJsonArray("alerts").get(0).getAsJsonObject().get("raisedTick").getAsLong());
		sample(b, DigestBook.DAY + 600, 20, 0.8, false);
		assertEquals(0, b.alertsJson(DigestBook.DAY * 2).getAsJsonArray("alerts").size(), "moving on clears it");
	}

	@Test
	void starvationAndDeathAreAlerts() {
		DigestBook b = new DigestBook();
		sample(b, 0, 0, 0.05, false);
		assertEquals("starving", b.alertsJson(DigestBook.DAY * 2).getAsJsonArray("alerts").get(0).getAsJsonObject().get("kind").getAsString());
		b.event(ev(100, EventType.DEATH, "Wilder starved to death.", WILDER), NAMES::get, 0);
		var alerts = b.alertsJson(DigestBook.DAY * 2).getAsJsonArray("alerts");
		assertEquals(1, alerts.size());
		assertEquals("death", alerts.get(0).getAsJsonObject().get("kind").getAsString());
	}

	@Test
	void theAccountSurvivesSaveAndLoadAndKeepsItsPlaceInTheLog() {
		DigestBook b = new DigestBook();
		sample(b, 0, 0, 0.8, true);
		b.event(ev(10, EventType.ACTION, "Wilder ate some bread.", WILDER), NAMES::get, 0);
		DigestBook loaded = DigestBook.load(b.save());
		assertEquals(b.lastEventId(), loaded.lastEventId());
		assertEquals(b.toJson().toString(), loaded.toJson().toString());
	}

	@Test
	void aDeathLongAgoIsNoAlertAndADayStartsWhereTheAgentIs() {
		DigestBook b = new DigestBook();
		b.event(ev(10, EventType.ACTION, "Wilder ate some bread.", WILDER), NAMES::get, 0);
		sample(b, 20, 500, 0.8, false);
		JsonObject day = wilder(b).getAsJsonArray("days").get(0).getAsJsonObject();
		assertEquals(500, day.get("startX").getAsInt());
		assertEquals(0.0, day.get("farthest").getAsDouble(), 1e-9);
		b.event(ev(100, EventType.DEATH, "Wilder starved to death.", WILDER), NAMES::get, 0);
		assertEquals(1, b.alertsJson(100 + DigestBook.DAY).getAsJsonArray("alerts").size());
		assertEquals(0, b.alertsJson(100 + 4 * DigestBook.DAY).getAsJsonArray("alerts").size());
	}
}
