package com.aicivilization.observer;

import com.aicivilization.events.EventType;
import com.aicivilization.events.SimEvent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicSitePublisherTest {

	private static final UUID IRIS = UUID.randomUUID();

	private static ObserverSnapshot snapshot() {
		List<SimEvent> events = List.of(
				new SimEvent(1, 10, EventType.DECISION, List.of(IRIS), "Iris decided to forage.", List.of()),
				new SimEvent(2, 20, EventType.ACTION, List.of(IRIS), "Iris hunted a cow.", List.of()));
		return new ObserverSnapshot(100, "{\"ready\":true,\"day\":1,\"llmSpentThisMonthUsd\":3.5,\"forcedChunks\":4}", "[]",
				Map.of(IRIS.toString(), "{\"name\":\"Iris\"}"), events, Map.of(IRIS, "Iris"), "{\"stories\":[],\"routine\":[]}",
				"{\"stories\":[]}", "{}", "{\"agents\":[]}", "{\"alerts\":[]}");
	}

	@Test
	void thePublicCopyLeavesOutWhatIsPrivate() {
		JsonObject live = JsonParser.parseString(PublicSitePublisher.liveJson(snapshot(), 5)).getAsJsonObject();
		JsonObject overview = live.getAsJsonObject("overview");
		assertEquals(1, overview.get("day").getAsInt());
		assertFalse(overview.has("llmSpentThisMonthUsd"));
		assertFalse(overview.has("forcedChunks"));
		assertEquals(1, live.getAsJsonArray("events").size(), "decisions stay private");
		assertFalse(live.has("digest"));
		JsonObject details = JsonParser.parseString(PublicSitePublisher.detailsJson(snapshot())).getAsJsonObject();
		assertEquals("Iris", details.getAsJsonObject(IRIS.toString()).get("name").getAsString());
		assertTrue(PublicSitePublisher.staticPage("<body>\n<script src=\"app.js\"></script>\n</body>")
				.contains("window.CHRONICLE_STATIC = true"));
	}

	@Test
	void uploadsGoIntoTheBucketWithTheirTypeAndCaching() throws Exception {
		Map<String, String[]> got = new ConcurrentHashMap<>();
		HttpServer gcs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		gcs.createContext("/", ex -> {
			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			String name = body.replaceAll("(?s).*?\"name\":\"([^\"]+)\".*", "$1");
			got.put(name, new String[] {ex.getRequestMethod(), ex.getRequestURI().toString(),
					ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("Content-Type"), body});
			ex.sendResponseHeaders(200, -1);
			ex.close();
		});
		gcs.start();
		try {
			new PublicSitePublisher("http://127.0.0.1:" + gcs.getAddress().getPort(), "commons", () -> "tok", 20).publish(snapshot());
			long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (got.size() < 4 && System.nanoTime() < until) {
				Thread.sleep(50);
			}
			assertTrue(got.keySet().containsAll(List.of("index.html", "app.js", "live.json", "details.json")), got.keySet().toString());
			assertFalse(got.containsKey("terrain.json"), "no map until one is drawn");
			String[] live = got.get("live.json");
			assertEquals("POST", live[0]);
			assertEquals("/upload/storage/v1/b/commons/o?uploadType=multipart", live[1]);
			assertEquals("Bearer tok", live[2]);
			assertTrue(live[3].startsWith("multipart/related; boundary="));
			assertTrue(live[4].contains("\"cacheControl\":\"public, max-age=10\""), live[4]);
			assertTrue(live[4].contains("\"contentType\":\"application/json\""));
			assertTrue(live[4].contains("\"overview\""), "the data itself follows the metadata");
			assertTrue(got.get("index.html")[4].contains("window.CHRONICLE_STATIC = true"));
		} finally {
			gcs.stop(0);
		}
	}
}
