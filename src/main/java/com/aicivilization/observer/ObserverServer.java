package com.aicivilization.observer;

import com.aicivilization.events.SimEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only HTTP server for the observer page, using the JDK's built-in
 * server. Handlers only read the latest {@link ObserverSnapshot}, which is
 * immutable, so they never touch live simulation state.
 *
 * <p>Static files ({@code /}, {@code /app.js}) are public; every
 * {@code /api/} route requires the configured token as {@code ?t=} or an
 * {@code X-Observer-Token} header, unless the token is empty, which leaves
 * the API open.
 */
public final class ObserverServer {

	private static final Logger LOGGER = LoggerFactory.getLogger("aicivilization");
	static final int DEFAULT_EVENT_LIMIT = 100;
	static final int MAX_EVENT_LIMIT = 500;
	private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
			+ "connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

	private final byte[] token;
	private final HttpServer server;
	private final ExecutorService executor;
	private volatile ObserverSnapshot snapshot = ObserverSnapshot.empty();

	public ObserverServer(int port, String token) throws IOException {
		this.token = token.getBytes(StandardCharsets.UTF_8);
		this.server = HttpServer.create(new InetSocketAddress(port), 0);
		this.executor = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "aicivilization-observer");
			t.setDaemon(true);
			return t;
		});
		server.setExecutor(executor);
		server.createContext("/", this::handle);
	}

	public void start() {
		server.start();
	}

	public void stop() {
		server.stop(0);
		executor.shutdownNow();
	}

	/** The bound port, which differs from the requested one when that was 0. */
	public int port() {
		return server.getAddress().getPort();
	}

	public void publish(ObserverSnapshot next) {
		this.snapshot = next;
	}

	private void handle(HttpExchange ex) throws IOException {
		try (ex) {
			if (!"GET".equals(ex.getRequestMethod())) {
				send(ex, 405, "text/plain; charset=utf-8", "Method not allowed");
				return;
			}
			String path = ex.getRequestURI().getPath();
			switch (path) {
				case "/", "/index.html" -> sendResource(ex, "index.html", "text/html; charset=utf-8");
				case "/app.js" -> sendResource(ex, "app.js", "text/javascript; charset=utf-8");
				case "/favicon.ico" -> {
					ex.sendResponseHeaders(204, -1);
				}
				default -> {
					if (!path.startsWith("/api/")) {
						send(ex, 404, "text/plain; charset=utf-8", "Not found");
					} else if (!authorized(ex)) {
						sendJson(ex, 401, "{\"error\":\"missing or wrong token\"}");
					} else {
						route(ex, path.substring("/api/".length()), query(ex.getRequestURI().getRawQuery()));
					}
				}
			}
		} catch (RuntimeException e) {
			LOGGER.warn("Observer request failed", e);
		}
	}

	private void route(HttpExchange ex, String route, Map<String, String> q) throws IOException {
		ObserverSnapshot s = snapshot;
		if (route.equals("overview")) {
			sendJson(ex, 200, s.overviewJson());
		} else if (route.equals("agents")) {
			sendJson(ex, 200, s.agentsJson());
		} else if (route.startsWith("agents/")) {
			String detail = s.agentDetailJson().get(route.substring("agents/".length()));
			if (detail == null) {
				sendJson(ex, 404, "{\"error\":\"no such agent\"}");
			} else {
				sendJson(ex, 200, detail);
			}
		} else if (route.equals("events")) {
			sendJson(ex, 200, events(s, q).toString());
		} else if (route.startsWith("events/")) {
			Optional<SimEvent> found = parseLong(route.substring("events/".length()))
					.flatMap(id -> s.events().stream().filter(e -> e.id() == id).findFirst());
			if (found.isEmpty()) {
				sendJson(ex, 404, "{\"error\":\"event not in the retained window\"}");
			} else {
				sendJson(ex, 200, ObserverJson.event(found.get(), s::nameOf).toString());
			}
		} else {
			sendJson(ex, 404, "{\"error\":\"unknown route\"}");
		}
	}

	/**
	 * Newest-first page of retained events. {@code since} returns only newer
	 * events (for polling), {@code before} pages back; {@code type},
	 * {@code exclude} (a type to leave out) and {@code agent} filter.
	 */
	static JsonObject events(ObserverSnapshot s, Map<String, String> q) {
		long since = parseLong(q.get("since")).orElse(0L);
		long before = parseLong(q.get("before")).orElse(Long.MAX_VALUE);
		int limit = (int) Math.max(1, Math.min(MAX_EVENT_LIMIT, parseLong(q.get("limit")).orElse((long) DEFAULT_EVENT_LIMIT)));
		String type = q.get("type");
		String exclude = q.get("exclude");
		UUID agent = parseUuid(q.get("agent"));

		JsonArray out = new JsonArray();
		List<SimEvent> events = s.events();
		boolean more = false;
		for (int i = events.size() - 1; i >= 0; i--) {
			SimEvent e = events.get(i);
			if (e.id() <= since) {
				break;
			}
			if (e.id() >= before
					|| (type != null && !type.isEmpty() && !e.type().name().equals(type))
					|| (exclude != null && e.type().name().equals(exclude))
					|| (agent != null && !e.subjects().contains(agent))) {
				continue;
			}
			if (out.size() == limit) {
				more = true;
				break;
			}
			out.add(ObserverJson.event(e, s::nameOf));
		}
		JsonObject o = new JsonObject();
		o.add("events", out);
		o.addProperty("more", more);
		o.addProperty("oldestRetainedId", events.isEmpty() ? 0 : events.get(0).id());
		return o;
	}

	private boolean authorized(HttpExchange ex) {
		if (token.length == 0) {
			return true;
		}
		String given = query(ex.getRequestURI().getRawQuery()).get("t");
		if (given == null) {
			given = ex.getRequestHeaders().getFirst("X-Observer-Token");
		}
		return given != null && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8));
	}

	private void sendResource(HttpExchange ex, String name, String contentType) throws IOException {
		try (InputStream in = ObserverServer.class.getResourceAsStream("/observer/" + name)) {
			if (in == null) {
				send(ex, 500, "text/plain; charset=utf-8", "Missing resource " + name);
				return;
			}
			send(ex, 200, contentType, in.readAllBytes());
		}
	}

	private static void sendJson(HttpExchange ex, int status, String body) throws IOException {
		send(ex, status, "application/json; charset=utf-8", body);
	}

	private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
		send(ex, status, contentType, body.getBytes(StandardCharsets.UTF_8));
	}

	private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
		var h = ex.getResponseHeaders();
		h.set("Content-Type", contentType);
		h.set("Cache-Control", "no-store");
		h.set("X-Content-Type-Options", "nosniff");
		// The token is in the page URL; never send it on to other sites.
		h.set("Referrer-Policy", "no-referrer");
		h.set("Content-Security-Policy", CSP);
		ex.sendResponseHeaders(status, body.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(body);
		}
	}

	static Map<String, String> query(String raw) {
		Map<String, String> q = new HashMap<>();
		if (raw == null || raw.isEmpty()) {
			return q;
		}
		for (String part : raw.split("&")) {
			int eq = part.indexOf('=');
			String key = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
			String value = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
			q.putIfAbsent(key, value);
		}
		return q;
	}

	private static Optional<Long> parseLong(String s) {
		if (s == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(Long.parseLong(s));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	private static UUID parseUuid(String s) {
		if (s == null || s.isEmpty()) {
			return null;
		}
		try {
			return UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
