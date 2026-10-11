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
	void uploadsAreSignedPutsIntoTheBucket() throws Exception {
		Map<String, String[]> got = new ConcurrentHashMap<>();
		HttpServer s3 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		s3.createContext("/", ex -> {
			byte[] body = ex.getRequestBody().readAllBytes();
			got.put(ex.getRequestURI().getPath(), new String[] {ex.getRequestMethod(),
					ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("x-amz-date"),
					ex.getRequestHeaders().getFirst("x-amz-content-sha256"), S3Signer.sha256Hex(body),
					ex.getRequestHeaders().getFirst("Host"), ex.getRequestHeaders().getFirst("Cache-Control")});
			ex.sendResponseHeaders(200, -1);
			ex.close();
		});
		s3.start();
		try {
			String endpoint = "http://127.0.0.1:" + s3.getAddress().getPort();
			new PublicSitePublisher(endpoint, "chronicle", "KEYID", "SECRET", "auto", 20).publish(snapshot());
			long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (got.size() < 4 && System.nanoTime() < until) {
				Thread.sleep(50);
			}
			assertTrue(got.keySet().containsAll(List.of("/chronicle/index.html", "/chronicle/app.js", "/chronicle/live.json",
					"/chronicle/details.json")), got.keySet().toString());
			assertFalse(got.containsKey("/chronicle/terrain.json"), "no map until one is drawn");
			String[] live = got.get("/chronicle/live.json");
			assertEquals("PUT", live[0]);
			assertEquals(live[3], live[4], "the payload hash is the body's");
			assertEquals("public, max-age=10", live[6]);
			String expected = new S3Signer("KEYID", "SECRET", "auto").authorization("PUT", "/chronicle/live.json",
					Map.of("host", live[5], "x-amz-content-sha256", live[3], "x-amz-date", live[2]), live[3], live[2]);
			assertEquals(expected, live[1], "the signature checks out against what was received");
		} finally {
			s3.stop(0);
		}
	}
}
