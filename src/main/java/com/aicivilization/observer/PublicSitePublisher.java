package com.aicivilization.observer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a read-only copy of the observer page in a public Google Cloud
 * Storage bucket, written with the VM's own credentials, so anyone can
 * watch without reaching the game server: the page and its script once, and
 * the data as a few static JSON files, refreshed every so often:
 * {@code live.json} (overview, people, recent happenings, the chronicle),
 * {@code details.json} (each person's page) and {@code terrain.json} (the
 * map). Nothing private goes out: no token, no running costs, no
 * day-by-day digest or alerts. Uploads run off the server thread; one still
 * in flight is never stacked behind.
 */
public final class PublicSitePublisher {

	private static final Logger LOGGER = LoggerFactory.getLogger("aicivilization");
	/** How many of the newest happenings the public copy carries. */
	static final int EVENTS = 300;
	/** Overview fields that are for the operator, not the public. */
	static final Set<String> PRIVATE_OVERVIEW = Set.of("llmSpentThisMonthUsd", "llmCallsThisMonth", "llmMonthlyBudgetUsd",
			"bodyScan", "knownBodyChunks", "forcedChunks", "reasoningProvider");

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final String apiBase;
	private final String bucket;
	private final java.util.function.Supplier<String> token;
	private final long liveEveryMillis;
	private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private volatile boolean pageSent;
	private int failures;

	/**
	 * @param bucket the Google Cloud Storage bucket the public page is served from
	 * @param token  an OAuth access token with write access to it (the VM's own, see GoogleCredentials)
	 */
	public PublicSitePublisher(String bucket, java.util.function.Supplier<String> token, int liveEverySeconds) {
		this("https://storage.googleapis.com", bucket, token, liveEverySeconds);
	}

	PublicSitePublisher(String apiBase, String bucket, java.util.function.Supplier<String> token, int liveEverySeconds) {
		this.apiBase = apiBase.replaceAll("/+$", "");
		this.bucket = bucket;
		this.token = token;
		this.liveEveryMillis = Math.max(5, liveEverySeconds) * 1000L;
	}

	/** Called with each fresh snapshot; uploads whatever is due. */
	public void publish(ObserverSnapshot s) {
		long now = System.currentTimeMillis();
		if (!pageSent) {
			String page = resource("index.html");
			String script = resource("app.js");
			if (page != null && script != null) {
				put("index.html", staticPage(page).getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8", "public, max-age=300");
				put("app.js", script.getBytes(StandardCharsets.UTF_8), "text/javascript; charset=utf-8", "public, max-age=300");
				pageSent = true;
			}
		}
		if (due("live.json", now, liveEveryMillis)) {
			put("live.json", liveJson(s, now).getBytes(StandardCharsets.UTF_8), "application/json", "public, max-age=10");
		}
		if (due("details.json", now, liveEveryMillis * 3)) {
			put("details.json", detailsJson(s).getBytes(StandardCharsets.UTF_8), "application/json", "public, max-age=30");
		}
		if (!"{}".equals(s.terrainJson()) && due("terrain.json", now, 600_000)) {
			put("terrain.json", s.terrainJson().getBytes(StandardCharsets.UTF_8), "application/json", "public, max-age=300");
		}
	}

	private boolean due(String key, long now, long every) {
		Long last = lastSent.get(key);
		if (last != null && now - last < every) {
			return false;
		}
		lastSent.put(key, now);
		return true;
	}

	/** The page marked as the public copy: it reads the JSON files beside it instead of the live API. */
	static String staticPage(String page) {
		return page.replace("<script src=\"app.js\"></script>",
				"<script>window.CHRONICLE_STATIC = true;</script>\n<script src=\"app.js\"></script>");
	}

	static String liveJson(ObserverSnapshot s, long nowMillis) {
		JsonObject overview = JsonParser.parseString(s.overviewJson()).getAsJsonObject();
		PRIVATE_OVERVIEW.forEach(overview::remove);
		JsonObject o = new JsonObject();
		o.add("overview", overview);
		o.add("agents", JsonParser.parseString(s.agentsJson()));
		o.add("events", ObserverServer.events(s, Map.of("limit", String.valueOf(EVENTS), "exclude", "DECISION")).get("events"));
		o.add("stories", JsonParser.parseString(s.storiesJson()));
		o.addProperty("publishedAtMillis", nowMillis);
		return o.toString();
	}

	static String detailsJson(ObserverSnapshot s) {
		StringBuilder b = new StringBuilder("{");
		Map<String, String> sorted = new LinkedHashMap<>();
		s.agentDetailJson().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> sorted.put(e.getKey(), e.getValue()));
		boolean first = true;
		for (Map.Entry<String, String> e : sorted.entrySet()) {
			if (!first) {
				b.append(',');
			}
			first = false;
			b.append(new com.google.gson.JsonPrimitive(e.getKey())).append(':').append(e.getValue());
		}
		return b.append('}').toString();
	}

	private void put(String key, byte[] body, String contentType, String cacheControl) {
		if (!inFlight.add(key)) {
			return;
		}
		HttpRequest request;
		try {
			// One multipart upload: the object's metadata (so its caching and type are right), then its bytes.
			String boundary = "aiciv" + Long.toHexString(System.nanoTime());
			JsonObject meta = new JsonObject();
			meta.addProperty("name", key);
			meta.addProperty("contentType", contentType);
			meta.addProperty("cacheControl", cacheControl);
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			out.writeBytes(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta
					+ "\r\n--" + boundary + "\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			out.writeBytes(body);
			out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
			request = HttpRequest.newBuilder(URI.create(apiBase + "/upload/storage/v1/b/" + bucket + "/o?uploadType=multipart"))
					.timeout(Duration.ofSeconds(30))
					.header("Authorization", "Bearer " + token.get())
					.header("Content-Type", "multipart/related; boundary=" + boundary)
					.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
					.build();
		} catch (RuntimeException e) {
			inFlight.remove(key);
			if (failures++ % 50 == 0) {
				LOGGER.warn("Public site upload of {} couldn't start ({}); will keep trying.", key, e.toString());
			}
			return;
		}
		client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.whenComplete((response, error) -> {
					inFlight.remove(key);
					if (error != null || response.statusCode() >= 300) {
						if (failures++ % 50 == 0) {
							LOGGER.warn("Public site upload of {} failed ({}); will keep trying.", key,
									error != null ? error.toString() : "HTTP " + response.statusCode() + ": "
											+ response.body().substring(0, Math.min(200, response.body().length())));
						}
						if ("index.html".equals(key) || "app.js".equals(key)) {
							pageSent = false;
						}
					} else if (failures > 0) {
						failures = 0;
						LOGGER.info("Public site uploads are working again.");
					}
				});
	}

	private static String resource(String name) {
		try (InputStream in = PublicSitePublisher.class.getResourceAsStream("/observer/" + name)) {
			return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
	}
}
