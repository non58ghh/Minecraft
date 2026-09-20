package com.aicivilization.reasoning;

import com.aicivilization.mind.IntentType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A real, functional {@link ReasoningProvider} backed by the Claude Messages
 * API. Configured via {@code anicivilization.json} ({@code anthropicApiKeyEnv},
 * {@code anthropicModel}); if either is unset, every call falls back to
 * {@link HeuristicReasoningProvider} and logs a warning rather than failing
 * the server. {@code AgentContext} is the only input, so the epistemic rule
 * holds even when the LLM does the thinking — it only ever sees one agent's
 * own state, never the world or another agent's mind.
 */
public final class AnthropicReasoningProvider implements ReasoningProvider {

	private static final Logger LOGGER = LoggerFactory.getLogger("aicivilization");
	private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
	private static final String ANTHROPIC_VERSION = "2023-06-01";

	private final HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	private final ReasoningProvider fallback = new HeuristicReasoningProvider();
	private final String apiKey;
	private final String model;
	private final int maxTokens;

	public AnthropicReasoningProvider(String apiKey, String model, int maxTokens) {
		this.apiKey = apiKey;
		this.model = model;
		this.maxTokens = maxTokens;
	}

	@Override
	public CompletableFuture<ReasoningResult> reason(AgentContext context) {
		if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
			LOGGER.warn("Anthropic reasoning provider is not configured (missing API key or model); "
					+ "falling back to heuristic reasoning for {}.", context.agentName());
			return fallback.reason(context);
		}

		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.addProperty("content", context.toPromptSummary());
		JsonArray messages = new JsonArray();
		messages.add(message);

		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("max_tokens", maxTokens);
		body.add("messages", messages);

		HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
				.timeout(Duration.ofSeconds(20))
				.header("x-api-key", apiKey)
				.header("anthropic-version", ANTHROPIC_VERSION)
				.header("content-type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();

		return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(this::parseResponse)
				.exceptionally(ex -> {
					LOGGER.warn("Anthropic reasoning call failed for {}; falling back to heuristic reasoning.",
							context.agentName(), ex);
					return fallback.reason(context).join();
				});
	}

	private ReasoningResult parseResponse(HttpResponse<String> response) {
		try {
			if (response.statusCode() != 200) {
				LOGGER.warn("Anthropic API returned status {}: {}", response.statusCode(), response.body());
				return ReasoningResult.none();
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonArray content = root.getAsJsonArray("content");
			if (content == null || content.isEmpty()) {
				return ReasoningResult.none();
			}
			String text = content.get(0).getAsJsonObject().get("text").getAsString();
			JsonObject parsed = JsonParser.parseString(extractJson(text)).getAsJsonObject();

			Optional<String> goal = optionalString(parsed, "goal");
			Optional<IntentType> relatedIntent = optionalString(parsed, "relatedIntent").flatMap(AnthropicReasoningProvider::parseIntent);
			double priority = parsed.has("priority") ? parsed.get("priority").getAsDouble() : 0.5;
			Optional<String> belief = optionalString(parsed, "belief");
			double beliefConfidence = parsed.has("beliefConfidence") ? parsed.get("beliefConfidence").getAsDouble() : 0.0;

			return new ReasoningResult(goal, relatedIntent, priority, belief, beliefConfidence);
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to parse Anthropic response; ignoring this reasoning pass.", e);
			return ReasoningResult.none();
		}
	}

	private static Optional<IntentType> parseIntent(String value) {
		try {
			return Optional.of(IntentType.valueOf(value.trim().toUpperCase(Locale.ROOT)));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private static Optional<String> optionalString(JsonObject obj, String key) {
		if (!obj.has(key) || obj.get(key).isJsonNull()) {
			return Optional.empty();
		}
		String value = obj.get(key).getAsString();
		return value.isBlank() ? Optional.empty() : Optional.of(value);
	}

	/** Models sometimes wrap JSON in prose or code fences; extract the {...} block. */
	private static String extractJson(String text) {
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start >= 0 && end > start) {
			return text.substring(start, end + 1);
		}
		return text;
	}
}
