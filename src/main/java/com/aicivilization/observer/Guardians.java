package com.aicivilization.observer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Who is Guardian of whom, read from Stripe: completed checkouts of the
 * Guardian payment link carry the agent's id (as client_reference_id) and
 * the name the payer chose; a Guardian counts while that checkout's
 * subscription is active (or trialing, or past due while Stripe retries).
 * Polled off the server thread every so often; the observer only reads the
 * last answer. Only the chosen name goes public, never who paid.
 */
public final class Guardians {

	private static final Logger LOGGER = LoggerFactory.getLogger("aicivilization");
	/** The checkout's custom field holding the name to show. */
	static final String NAME_FIELD = "guardianname";
	static final int MAX_NAME = 30;
	private static final Set<String> STANDING = Set.of("active", "trialing", "past_due");

	/** One Guardian: the name shown, and when it began (epoch seconds). */
	public record Guardian(String name, long since) {
	}

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final String apiBase;
	private final String paymentLink;
	private final Supplier<String> key;
	private final long everyMillis;
	private final AtomicBoolean polling = new AtomicBoolean();
	private volatile Map<UUID, Guardian> byAgent = Map.of();
	private volatile long lastPoll;
	private int failures;

	public Guardians(String paymentLink, Supplier<String> key, int everySeconds) {
		this("https://api.stripe.com", paymentLink, key, everySeconds);
	}

	Guardians(String apiBase, String paymentLink, Supplier<String> key, int everySeconds) {
		this.apiBase = apiBase.replaceAll("/+$", "");
		this.paymentLink = paymentLink;
		this.key = key;
		this.everyMillis = Math.max(60, everySeconds) * 1000L;
	}

	/** The Guardian of this agent, if it has one. */
	public Guardian of(UUID agent) {
		return byAgent.get(agent);
	}

	/** Starts a poll if one is due and none is running. Returns at once. */
	public void maybePoll() {
		long now = System.currentTimeMillis();
		if (now - lastPoll < everyMillis || !polling.compareAndSet(false, true)) {
			return;
		}
		lastPoll = now;
		Thread.ofVirtual().name("aiciv-guardians").start(() -> {
			try {
				byAgent = fetch();
				if (failures > 0) {
					LOGGER.info("Guardians are being read from Stripe again.");
				}
				failures = 0;
			} catch (RuntimeException e) {
				if (failures++ % 20 == 0) {
					LOGGER.warn("Couldn't read Guardians from Stripe ({}); keeping the last list.", e.getMessage());
				}
			} finally {
				polling.set(false);
			}
		});
	}

	/** One full read: the standing subscriptions, then the link's completed checkouts that belong to them. */
	Map<UUID, Guardian> fetch() {
		Set<String> standing = new HashSet<>();
		for (JsonObject sub : list("/v1/subscriptions?status=all&limit=100")) {
			if (STANDING.contains(text(sub, "status"))) {
				standing.add(text(sub, "id"));
			}
		}
		Map<UUID, Guardian> out = new HashMap<>();
		for (JsonObject session : list("/v1/checkout/sessions?status=complete&limit=100&payment_link="
				+ URLEncoder.encode(paymentLink, StandardCharsets.UTF_8))) {
			UUID agent = uuid(text(session, "client_reference_id"));
			String name = guardianName(session);
			long since = session.has("created") ? session.get("created").getAsLong() : 0;
			if (agent == null || name == null || !standing.contains(text(session, "subscription"))) {
				continue;
			}
			// The longest-standing Guardian keeps the place.
			out.merge(agent, new Guardian(name, since), (a, b) -> a.since() <= b.since() ? a : b);
		}
		return Map.copyOf(out);
	}

	/** Every object on every page of a Stripe list. */
	private Iterable<JsonObject> list(String first) {
		java.util.List<JsonObject> all = new java.util.ArrayList<>();
		String path = first;
		for (int page = 0; page < 50 && path != null; page++) {
			JsonObject o = get(path);
			JsonArray data = o.getAsJsonArray("data");
			String last = null;
			for (JsonElement e : data) {
				all.add(e.getAsJsonObject());
				last = text(e.getAsJsonObject(), "id");
			}
			path = o.has("has_more") && o.get("has_more").getAsBoolean() && last != null
					? first + "&starting_after=" + URLEncoder.encode(last, StandardCharsets.UTF_8) : null;
		}
		return all;
	}

	private JsonObject get(String path) {
		String secret = key.get();
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException("no Stripe key");
		}
		try {
			HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(apiBase + path))
					.timeout(Duration.ofSeconds(20))
					.header("Authorization", "Bearer " + secret.strip())
					.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IllegalStateException("Stripe answered " + response.statusCode());
			}
			return JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted", e);
		}
	}

	/** The name the payer chose, tidied: one line, no control characters, at most {@link #MAX_NAME} long. */
	static String guardianName(JsonObject session) {
		if (!session.has("custom_fields") || !session.get("custom_fields").isJsonArray()) {
			return null;
		}
		for (JsonElement f : session.getAsJsonArray("custom_fields")) {
			JsonObject field = f.getAsJsonObject();
			if (!NAME_FIELD.equals(text(field, "key")) || !field.has("text") || !field.get("text").isJsonObject()) {
				continue;
			}
			String raw = text(field.getAsJsonObject("text"), "value");
			if (raw == null) {
				return null;
			}
			String clean = raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
			if (clean.length() > MAX_NAME) {
				clean = clean.substring(0, MAX_NAME).strip();
			}
			return clean.isEmpty() ? null : clean;
		}
		return null;
	}

	private static String text(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}

	private static UUID uuid(String s) {
		try {
			return s == null ? null : UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
