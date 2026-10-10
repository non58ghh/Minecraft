package com.aicivilization.reasoning;

import com.aicivilization.mind.Design;
import com.aicivilization.mind.DesignGenerator;
import com.aicivilization.mind.DesignValidator;
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
import java.util.ArrayList;
import java.util.List;
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
	/** A drawing of up to 8 layers of 13 rows of 13 needs far more room than a goal and a belief. */
	private static final int DESIGN_MAX_TOKENS = 3000;
	/** Writing that much takes a while; it's one call per agent, ever, so it can wait. */
	private static final Duration DESIGN_TIMEOUT = Duration.ofSeconds(120);
	private static final int DIALOGUE_MAX_TOKENS = 600;
	private static final int STORY_MAX_TOKENS = 700;
	/**
	 * A full answer (goal, belief and target as JSON) runs to 100-250 tokens;
	 * at the old default of 150 replies were cut off and lost. Lower
	 * configured values are raised to this.
	 */
	static final int MIN_REASONING_TOKENS = 300;

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
		this.maxTokens = Math.max(MIN_REASONING_TOKENS, maxTokens);
	}

	private boolean configured() {
		return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
	}

	private HttpRequest request(String prompt, int tokens) {
		return request(prompt, tokens, Duration.ofSeconds(30));
	}

	private HttpRequest request(String prompt, int tokens, Duration timeout) {
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.addProperty("content", prompt);
		JsonArray messages = new JsonArray();
		messages.add(message);

		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("max_tokens", tokens);
		body.add("messages", messages);

		return HttpRequest.newBuilder(ENDPOINT)
				.timeout(timeout)
				.header("x-api-key", apiKey)
				.header("anthropic-version", ANTHROPIC_VERSION)
				.header("content-type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
	}

	/**
	 * One call per agent, ever: Claude draws the agent's home. The answer is
	 * checked by {@link DesignValidator}; anything that doesn't hold up is
	 * replaced by a procedurally drawn design.
	 */
	@Override
	public CompletableFuture<Optional<Design>> design(DesignBrief brief) {
		if (!configured()) {
			return fallback.design(brief);
		}
		return client.sendAsync(request(brief.toPrompt(), DESIGN_MAX_TOKENS, DESIGN_TIMEOUT), HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> parseDesign(response, brief))
				.exceptionally(ex -> {
					LOGGER.warn("Anthropic design call failed for {}; drawing one procedurally.", brief.agentName(), ex);
					return Optional.empty();
				})
				.thenCompose(design -> design.isPresent()
						? CompletableFuture.completedFuture(design)
						: fallback.design(brief));
	}

	@Override
	public CompletableFuture<Optional<Dialogue>> converse(DialogueBrief brief) {
		if (!configured()) {
			return CompletableFuture.completedFuture(Optional.empty());
		}
		return client.sendAsync(request(brief.toPrompt(), DIALOGUE_MAX_TOKENS), HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> parseDialogue(response, brief))
				.exceptionally(ex -> {
					LOGGER.warn("Anthropic dialogue call failed for {} and {}.", brief.first().name(), brief.second().name(), ex);
					return Optional.empty();
				});
	}

	@Override
	public CompletableFuture<Optional<StoryText>> narrate(StoryBrief brief) {
		if (!configured()) {
			return CompletableFuture.completedFuture(Optional.empty());
		}
		return client.sendAsync(request(brief.toPrompt(), STORY_MAX_TOKENS), HttpResponse.BodyHandlers.ofString())
				.thenApply(this::parseStory)
				.exceptionally(ex -> {
					LOGGER.warn("Anthropic story call failed.", ex);
					return Optional.empty();
				});
	}

	private Optional<StoryText> parseStory(HttpResponse<String> response) {
		try {
			if (response.statusCode() != 200) {
				LOGGER.warn("Anthropic API returned status {} for a story: {}", response.statusCode(), response.body());
				return Optional.empty();
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			String text = root.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
			return parseStoryJson(text);
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to parse a story from Claude.", e);
			return Optional.empty();
		}
	}

	/** The model's reply to a story prompt, if it holds a headline and a text. */
	static Optional<StoryText> parseStoryJson(String reply) {
		try {
			JsonObject parsed = JsonParser.parseString(extractJson(reply)).getAsJsonObject();
			String headline = clip(optionalString(parsed, "headline").orElse("").strip(), 120);
			String text = clip(optionalString(parsed, "text").orElse("").strip(), 1200);
			String stands = clip(optionalString(parsed, "stands").orElse("").strip(), 160);
			if (headline.isEmpty() || text.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(new StoryText(headline, text, stands));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private Optional<Dialogue> parseDialogue(HttpResponse<String> response, DialogueBrief brief) {
		try {
			if (response.statusCode() != 200) {
				LOGGER.warn("Anthropic API returned status {} for a conversation: {}", response.statusCode(), response.body());
				return Optional.empty();
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			String text = root.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
			JsonObject parsed = JsonParser.parseString(extractJson(text)).getAsJsonObject();
			List<String> lines = new ArrayList<>();
			for (var element : parsed.getAsJsonArray("lines")) {
				JsonObject line = element.getAsJsonObject();
				String who = "B".equalsIgnoreCase(optionalString(line, "speaker").orElse("A"))
						? brief.second().name() : brief.first().name();
				String said = optionalString(line, "text").orElse("").strip();
				if (!said.isEmpty() && lines.size() < 8) {
					lines.add(who + ": " + clip(said, 240));
				}
			}
			if (lines.isEmpty()) {
				return Optional.empty();
			}
			List<Dialogue.Agreement> agreements = new ArrayList<>();
			if (parsed.has("agreements") && parsed.get("agreements").isJsonArray()) {
				for (var element : parsed.getAsJsonArray("agreements")) {
					if (!element.isJsonObject() || agreements.size() >= 4) {
						continue;
					}
					try {
						parseAgreement(element.getAsJsonObject()).ifPresent(agreements::add);
					} catch (RuntimeException e) {
						// One garbled agreement doesn't spoil the conversation.
					}
				}
			}
			return Optional.of(new Dialogue(clip(optionalString(parsed, "topic").orElse("this and that").strip(), 80), lines,
					clip(optionalString(parsed, "a_remembers").orElse("").strip(), 200),
					clip(optionalString(parsed, "b_remembers").orElse("").strip(), 200), agreements));
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to parse a conversation from Claude for {} and {}.", brief.first().name(),
					brief.second().name(), e);
			return Optional.empty();
		}
	}

	static Optional<Dialogue.Agreement> parseAgreement(JsonObject a) {
		String type = optionalString(a, "type").orElse("").toLowerCase(Locale.ROOT);
		switch (type) {
			case "give" -> {
				boolean first = !"B".equalsIgnoreCase(optionalString(a, "from").orElse("A"));
				String item = optionalString(a, "item").orElse("").strip().toLowerCase(Locale.ROOT);
				int count = a.has("count") && a.get("count").isJsonPrimitive() ? a.get("count").getAsInt() : 1;
				if (item.isEmpty() || count <= 0) {
					return Optional.empty();
				}
				return Optional.of(new Dialogue.Agreement(Dialogue.Kind.GIVE, first, false,
						item.contains(":") ? item : "minecraft:" + item, count, null, null));
			}
			case "plan" -> {
				String who = optionalString(a, "who").orElse("A");
				String activity = optionalString(a, "activity").orElse("").strip().toUpperCase(Locale.ROOT);
				String goal = clip(optionalString(a, "goal").orElse("").strip(), 100);
				if (activity.isEmpty() || goal.isEmpty()) {
					return Optional.empty();
				}
				return Optional.of(new Dialogue.Agreement(Dialogue.Kind.PLAN, !"B".equalsIgnoreCase(who),
						"both".equalsIgnoreCase(who), null, 0, activity, goal));
			}
			case "build_together" -> {
				return Optional.of(new Dialogue.Agreement(Dialogue.Kind.BUILD_TOGETHER, true, true, null, 0, null, null));
			}
			default -> {
				return Optional.empty();
			}
		}
	}

	private static String clip(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max).strip() + "…";
	}

	private Optional<Design> parseDesign(HttpResponse<String> response, DesignBrief brief) {
		try {
			if (response.statusCode() != 200) {
				LOGGER.warn("Anthropic API returned status {} for a design: {}", response.statusCode(), response.body());
				return Optional.empty();
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			String text = root.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
			JsonObject parsed = JsonParser.parseString(extractJson(text)).getAsJsonObject();
			String kind = optionalString(parsed, "name").orElse("house").trim();
			if (kind.length() > 40) {
				kind = kind.substring(0, 40);
			}
			List<List<String>> layers = new ArrayList<>();
			for (var layer : parsed.getAsJsonArray("layers")) {
				List<String> rows = new ArrayList<>();
				for (var row : layer.getAsJsonArray()) {
					rows.add(row.getAsString());
				}
				layers.add(rows);
			}
			Design drawn = DesignValidator.repair(new Design("tmp", kind, layers));
			Optional<String> problem = DesignValidator.problem(drawn);
			if (problem.isPresent()) {
				LOGGER.info("{}'s design from Claude was not buildable ({}); drawing one procedurally.",
						brief.agentName(), problem.get());
				return Optional.empty();
			}
			String name = kind.toLowerCase(Locale.ROOT).startsWith(brief.agentName().toLowerCase(Locale.ROOT))
					? kind : brief.agentName() + "'s " + kind.toLowerCase(Locale.ROOT);
			return Optional.of(new Design(DesignGenerator.idFor(drawn), name, drawn.layers()));
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to parse a design from Claude for {}.", brief.agentName(), e);
			return Optional.empty();
		}
	}

	@Override
	public CompletableFuture<ReasoningResult> reason(AgentContext context) {
		if (!configured()) {
			LOGGER.warn("Anthropic reasoning provider is not configured (missing API key or model); "
					+ "falling back to heuristic reasoning for {}.", context.agentName());
			return fallback.reason(context);
		}

		return client.sendAsync(request(context.toPromptSummary(), maxTokens), HttpResponse.BodyHandlers.ofString())
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
				return ReasoningResult.failed("the API answered with status " + response.statusCode());
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonArray content = root.getAsJsonArray("content");
			if (content == null || content.isEmpty()) {
				return ReasoningResult.failed("the reply was empty");
			}
			if ("max_tokens".equals(optionalString(root, "stop_reason").orElse(""))) {
				LOGGER.warn("Anthropic reply was cut off at {} tokens; raise anthropicMaxTokens.", maxTokens);
				return ReasoningResult.failed("the reply was cut off before it finished");
			}
			String text = content.get(0).getAsJsonObject().get("text").getAsString();
			JsonObject parsed = JsonParser.parseString(extractJson(text)).getAsJsonObject();

			Optional<String> goal = optionalString(parsed, "goal");
			Optional<IntentType> relatedIntent = optionalString(parsed, "relatedIntent").flatMap(AnthropicReasoningProvider::parseIntent);
			double priority = parsed.has("priority") ? parsed.get("priority").getAsDouble() : 0.5;
			Optional<String> belief = optionalString(parsed, "belief");
			double beliefConfidence = parsed.has("beliefConfidence") ? parsed.get("beliefConfidence").getAsDouble() : 0.0;

			Optional<String> targetItem = Optional.empty();
			int targetCount = 0;
			if (parsed.has("target") && parsed.get("target").isJsonObject()) {
				JsonObject target = parsed.getAsJsonObject("target");
				targetItem = optionalString(target, "item").map(String::strip).filter(s -> !s.isEmpty());
				try {
					targetCount = target.has("count") ? target.get("count").getAsInt() : 1;
				} catch (RuntimeException e) {
					targetCount = 1;
				}
			}
			return new ReasoningResult(goal, relatedIntent, priority, belief, beliefConfidence, targetItem, targetCount);
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to parse Anthropic response; ignoring this reasoning pass.", e);
			return ReasoningResult.failed("the reply couldn't be read");
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
