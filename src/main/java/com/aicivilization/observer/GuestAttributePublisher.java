package com.aicivilization.observer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Copies the observer's data to the Google Compute Engine guest attributes
 * of the VM the server runs on (namespace {@code aiciv}), about once a
 * minute. Guest attributes can be read through the Compute API, so the
 * simulation can be checked from anywhere with project access, without
 * reaching the observer port. Off GCE the first write fails and publishing
 * stops quietly.
 */
public final class GuestAttributePublisher {

	private static final Logger LOGGER = LoggerFactory.getLogger("aicivilization");
	private static final String BASE =
			"http://metadata.google.internal/computeMetadata/v1/instance/guest-attributes/aiciv/";
	/** Guest attribute values are capped by GCE; stay well under it. */
	static final int MAX_VALUE_BYTES = 120_000;

	private final HttpClient client = HttpClient.newBuilder()
			.proxy(HttpClient.Builder.NO_PROXY)
			.connectTimeout(Duration.ofSeconds(2))
			.build();
	private volatile boolean disabled;

	public void publish(ObserverSnapshot s) {
		if (disabled) {
			return;
		}
		String events = ObserverServer.events(s, Map.of("limit", "80", "exclude", "DECISION")).toString();
		put("overview", s.overviewJson());
		put("agents", s.agentsJson());
		put("events", events);
		put("stories", s.recentStoriesJson());
	}

	private void put(String key, String value) {
		byte[] body = value.getBytes(StandardCharsets.UTF_8);
		if (body.length > MAX_VALUE_BYTES) {
			body = ("{\"truncated\":true,\"bytes\":" + body.length + "}").getBytes(StandardCharsets.UTF_8);
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + key))
				.timeout(Duration.ofSeconds(5))
				.header("Metadata-Flavor", "Google")
				.PUT(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
		client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
				.whenComplete((response, error) -> {
					if (error != null || response.statusCode() >= 400) {
						if (!disabled) {
							disabled = true;
							LOGGER.info("Not publishing observer data to guest attributes ({}); "
									+ "this is expected when not running on Google Compute Engine.",
									error != null ? error.getClass().getSimpleName() : "HTTP " + response.statusCode());
						}
					}
				});
	}
}
