package com.aicivilization.observer;

import com.aicivilization.events.EventType;
import com.aicivilization.events.SimEvent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObserverServerTest {

	private static final String TOKEN = "secret-token";
	private static final UUID IRIS = UUID.randomUUID();
	private static final UUID BRAM = UUID.randomUUID();

	private ObserverServer server;
	private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

	@BeforeEach
	void start() throws Exception {
		server = new ObserverServer(0, TOKEN);
		server.start();
		server.publish(new ObserverSnapshot(1000, "{\"ready\":true}", "[]",
				Map.of(IRIS.toString(), "{\"name\":\"Iris\"}"), sampleEvents(), Map.of(IRIS, "Iris", BRAM, "Bram")));
	}

	@AfterEach
	void stop() {
		server.stop();
	}

	private HttpResponse<String> get(String pathAndQuery) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + pathAndQuery)).build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void apiRejectsMissingOrWrongTokenButServesThePagePublicly() throws Exception {
		assertEquals(401, get("/api/overview").statusCode());
		assertEquals(401, get("/api/overview?t=wrong").statusCode());
		assertEquals(200, get("/api/overview?t=" + TOKEN).statusCode());

		HttpResponse<String> page = get("/");
		assertEquals(200, page.statusCode());
		assertTrue(page.body().contains("<html"));
		assertEquals("no-referrer", page.headers().firstValue("Referrer-Policy").orElse(""));
		assertEquals(200, get("/app.js").statusCode());
	}

	@Test
	void agentDetailAndSingleEventRoutes() throws Exception {
		assertEquals("Iris", JsonParser.parseString(get("/api/agents/" + IRIS + "?t=" + TOKEN).body())
				.getAsJsonObject().get("name").getAsString());
		assertEquals(404, get("/api/agents/" + BRAM + "?t=" + TOKEN).statusCode());

		JsonObject event = JsonParser.parseString(get("/api/events/4?t=" + TOKEN).body()).getAsJsonObject();
		assertEquals("Iris", event.getAsJsonArray("subjects").get(0).getAsJsonObject().get("name").getAsString());
		assertEquals(404, get("/api/events/99?t=" + TOKEN).statusCode());
	}

	@Test
	void eventsAreNewestFirstAndSupportSinceBeforeTypeAndAgent() throws Exception {
		ObserverSnapshot s = new ObserverSnapshot(1000, "{}", "[]", Map.of(), sampleEvents(), Map.of(IRIS, "Iris", BRAM, "Bram"));

		JsonObject page = ObserverServer.events(s, Map.of("limit", "3"));
		assertEquals(10, firstId(page));
		assertEquals(3, page.getAsJsonArray("events").size());
		assertTrue(page.get("more").getAsBoolean());

		assertEquals(2, ObserverServer.events(s, Map.of("since", "8")).getAsJsonArray("events").size());
		assertEquals(4, firstId(ObserverServer.events(s, Map.of("before", "5"))));

		JsonObject conversations = ObserverServer.events(s, Map.of("type", "CONVERSATION"));
		assertEquals(5, conversations.getAsJsonArray("events").size());
		assertFalse(conversations.get("more").getAsBoolean());

		JsonObject noDecisions = ObserverServer.events(s, Map.of("exclude", "DECISION"));
		assertEquals(5, noDecisions.getAsJsonArray("events").size());
		assertEquals(10, firstId(noDecisions));

		JsonObject bram = ObserverServer.events(s, Map.of("agent", BRAM.toString()));
		assertEquals(5, bram.getAsJsonArray("events").size());
		assertEquals(10, firstId(bram));
	}

	@Test
	void queryParsingDecodesValues() {
		Map<String, String> q = ObserverServer.query("t=a%2Bb&type=DEATH&flag");
		assertEquals("a+b", q.get("t"));
		assertEquals("DEATH", q.get("type"));
		assertEquals("", q.get("flag"));
	}

	private static long firstId(JsonObject page) {
		return page.getAsJsonArray("events").get(0).getAsJsonObject().get("id").getAsLong();
	}

	/** Ten events: odd ids are decisions, even ids conversations; 1-5 involve Iris, 6-10 Bram. */
	private static List<SimEvent> sampleEvents() {
		List<SimEvent> events = new ArrayList<>();
		for (long id = 1; id <= 10; id++) {
			events.add(new SimEvent(id, id * 100, id % 2 == 0 ? EventType.CONVERSATION : EventType.DECISION,
					List.of(id <= 5 ? IRIS : BRAM), "event " + id, List.of()));
		}
		return events;
	}
}
