package com.aicivilization.reasoning;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Google Cloud application default credentials as a VM sees them: an access
 * token for the service account attached to the instance, from the
 * metadata server, kept until shortly before it expires. Off Google Cloud
 * (for local runs), the environment variable {@code GOOGLE_OAUTH_ACCESS_TOKEN}
 * (e.g. from {@code gcloud auth print-access-token}) is used instead.
 */
public final class GoogleCredentials {

	private static final String METADATA = "http://metadata.google.internal/computeMetadata/v1/";
	/** Fetch a new token this long before the old one runs out. */
	private static final long REFRESH_EARLY_MILLIS = 120_000;

	private final HttpClient client = HttpClient.newBuilder()
			.proxy(HttpClient.Builder.NO_PROXY)
			.connectTimeout(Duration.ofSeconds(3))
			.build();
	private String token;
	private long expiresAtMillis;

	/** A current access token; throws if none can be had. */
	public synchronized String accessToken() {
		String fromEnv = System.getenv("GOOGLE_OAUTH_ACCESS_TOKEN");
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv.strip();
		}
		if (token == null || System.currentTimeMillis() > expiresAtMillis - REFRESH_EARLY_MILLIS) {
			JsonObject o = JsonParser.parseString(metadata("instance/service-accounts/default/token")).getAsJsonObject();
			token = o.get("access_token").getAsString();
			expiresAtMillis = System.currentTimeMillis() + o.get("expires_in").getAsLong() * 1000;
		}
		return token;
	}

	/** The project this VM belongs to, or blank off Google Cloud. */
	public String projectId() {
		try {
			return metadata("project/project-id").strip();
		} catch (RuntimeException e) {
			return "";
		}
	}

	private String metadata(String path) {
		try {
			HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(METADATA + path))
					.timeout(Duration.ofSeconds(5))
					.header("Metadata-Flavor", "Google")
					.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IllegalStateException("metadata server answered " + response.statusCode() + " for " + path);
			}
			return response.body();
		} catch (IOException e) {
			throw new IllegalStateException("no metadata server (not on Google Cloud?): " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted", e);
		}
	}
}
