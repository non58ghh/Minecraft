package com.aicivilization.reasoning;

import com.aicivilization.mind.Design;
import com.aicivilization.mind.IntentType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A {@link ReasoningProvider} backed by Gemini models on Google Cloud
 * Vertex AI, in two tiers. The fast tier (e.g. {@code gemini-2.5-flash},
 * thinking off) takes what happens often: each agent's routine thinking, its
 * home design and answers to players. The strong tier (e.g.
 * {@code gemini-2.5-pro}) takes the high-drama writing people read: agents'
 * conversations and chronicle write-ups, deaths among them. Every reply is
 * held to a JSON schema. Calls authenticate as the VM's service account
 * ({@link GoogleCredentials}).
 *
 * <p>Each kind of call sends its fixed part (how the world works, the
 * rules, the reply) as the system instruction, the same for every call of
 * that kind, and the particulars as the message. With caching on, the fixed
 * part is put in a Vertex AI context cache once and referred to by name; if
 * Vertex won't cache it (it has a minimum size), it's sent inline, where
 * Vertex's own implicit caching of repeated prefixes can still apply.
 */
public final class VertexGeminiProvider implements ReasoningProvider {

	private static final Logger LOGGER = LoggerFactory.getLogger("aicivilization");
	private static final int REASONING_MAX_TOKENS = 400;
	private static final int DESIGN_MAX_TOKENS = 3000;
	private static final int CHAT_MAX_TOKENS = 300;
	private static final int DIALOGUE_MAX_TOKENS = 900;
	private static final int STORY_MAX_TOKENS = 900;
	private static final int JOURNAL_MAX_TOKENS = 700;
	/** The strong tier can't turn thinking off; this much is allowed for it, on top of the answer. */
	static final int STRONG_THINKING_BUDGET = 512;
	private static final Duration FAST_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration STRONG_TIMEOUT = Duration.ofSeconds(90);
	/** A cache that won't be created (too small for Vertex, say) isn't tried again for this long. */
	static final long CACHE_RETRY_MILLIS = 6 * 3_600_000L;

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ReasoningProvider fallback = new HeuristicReasoningProvider();
	private final String apiBase;
	private final String project;
	private final String location;
	private final String fastModel;
	private final String strongModel;
	private final boolean caching;
	private final long cacheTtlSeconds;
	private final Supplier<String> token;
	private final SpendLedger ledger;
	private final Map<String, CacheEntry> caches = new ConcurrentHashMap<>();

	/** A context cache for one model and system instruction: its name once made, or why there's none. */
	private static final class CacheEntry {
		volatile String name;
		volatile long expiresAtMillis;
		volatile long retryAfterMillis;
		volatile boolean creating;
	}

	public VertexGeminiProvider(String project, String location, String fastModel, String strongModel, boolean caching,
			long cacheTtlSeconds, SpendLedger ledger) {
		this(endpointFor(location), project, location, fastModel, strongModel, caching, cacheTtlSeconds,
				new GoogleCredentials()::accessToken, ledger);
	}

	VertexGeminiProvider(String apiBase, String project, String location, String fastModel, String strongModel, boolean caching,
			long cacheTtlSeconds, Supplier<String> token, SpendLedger ledger) {
		this.apiBase = apiBase.replaceAll("/+$", "");
		this.project = project;
		this.location = location;
		this.fastModel = fastModel;
		this.strongModel = strongModel == null || strongModel.isBlank() ? fastModel : strongModel;
		this.caching = caching;
		this.cacheTtlSeconds = Math.max(300, cacheTtlSeconds);
		this.token = token;
		this.ledger = ledger;
	}

	/** {@code global} is served from the plain host; a region from its own. */
	static String endpointFor(String location) {
		return "global".equals(location) ? "https://aiplatform.googleapis.com"
				: "https://" + location + "-aiplatform.googleapis.com";
	}

	private String locationPath() {
		return "projects/" + project + "/locations/" + location;
	}

	private boolean strong(String model) {
		return model.equals(strongModel) && !strongModel.equals(fastModel);
	}

	// ------------------------------------------------------------------ calls

	@Override
	public CompletableFuture<ReasoningResult> reason(AgentContext context) {
		return generate(fastModel, context.promptParts(), REASONING_MAX_TOKENS, REASONING_SCHEMA)
				.thenApply(text -> text.map(t -> {
					try {
						return AnthropicReasoningProvider.reasoningFromText(t);
					} catch (RuntimeException e) {
						return ReasoningResult.failed("the reply couldn't be read");
					}
				}).orElseGet(() -> ReasoningResult.failed("the model gave no usable answer")))
				.exceptionally(ex -> {
					LOGGER.warn("Vertex AI reasoning call failed for {}; falling back to heuristic reasoning.", context.agentName(), ex);
					return fallback.reason(context).join();
				});
	}

	@Override
	public CompletableFuture<Optional<Design>> design(DesignBrief brief) {
		return generate(fastModel, brief.parts(), DESIGN_MAX_TOKENS, DESIGN_SCHEMA)
				.thenApply(text -> text.flatMap(parsing(t -> AnthropicReasoningProvider.designFromText(t, brief))))
				.exceptionally(ex -> {
					LOGGER.warn("Vertex AI design call failed for {}; drawing one procedurally.", brief.agentName(), ex);
					return Optional.empty();
				})
				.thenCompose(design -> design.isPresent() ? CompletableFuture.completedFuture(design) : fallback.design(brief));
	}

	@Override
	public CompletableFuture<Optional<ChatReply>> reply(ChatBrief brief) {
		return generate(fastModel, brief.parts(), CHAT_MAX_TOKENS, CHAT_SCHEMA)
				.thenApply(text -> text.flatMap(AnthropicReasoningProvider::parseChatJson))
				.exceptionally(ex -> {
					LOGGER.warn("Vertex AI chat call failed for {}.", brief.agent().name(), ex);
					return Optional.empty();
				});
	}

	@Override
	public CompletableFuture<Optional<Dialogue>> converse(DialogueBrief brief) {
		return generate(strongModel, brief.parts(), DIALOGUE_MAX_TOKENS, DIALOGUE_SCHEMA)
				.thenApply(text -> text.flatMap(parsing(t -> AnthropicReasoningProvider.dialogueFromText(t, brief))))
				.exceptionally(ex -> {
					LOGGER.warn("Vertex AI conversation call failed for {} and {}.", brief.first().name(), brief.second().name(), ex);
					return Optional.empty();
				});
	}

	@Override
	public CompletableFuture<Optional<StoryText>> narrate(StoryBrief brief) {
		return generate(strongModel, brief.parts(), STORY_MAX_TOKENS, STORY_SCHEMA)
				.thenApply(text -> text.flatMap(AnthropicReasoningProvider::parseStoryJson))
				.exceptionally(ex -> {
					LOGGER.warn("Vertex AI story call failed.", ex);
					return Optional.empty();
				});
	}

	@Override
	public CompletableFuture<Optional<String>> journal(JournalBrief brief) {
		return generate(strongModel, brief.parts(), JOURNAL_MAX_TOKENS, JOURNAL_SCHEMA)
				.thenApply(text -> text.flatMap(AnthropicReasoningProvider::journalFromText))
				.exceptionally(ex -> {
					LOGGER.warn("Vertex AI journal call failed for {}.", brief.name(), ex);
					return Optional.empty();
				});
	}

	private static <T> Function<String, Optional<T>> parsing(Function<String, Optional<T>> parse) {
		return t -> {
			try {
				return parse.apply(t);
			} catch (RuntimeException e) {
				return Optional.empty();
			}
		};
	}

	// ------------------------------------------------------------- the wire

	/** The reply's text, or empty if the model gave none worth reading (cut off, blocked). */
	CompletableFuture<Optional<String>> generate(String model, PromptParts prompt, int maxTokens, JsonObject schema) {
		return CompletableFuture.supplyAsync(() -> cacheFor(model, prompt.system()))
				.thenCompose(cache -> send(model, prompt, maxTokens, schema, cache)
						.thenCompose(response -> {
							if (cache != null && response.statusCode() >= 400 && response.statusCode() < 500) {
								// The cache has gone (expired early, deleted): forget it and send the instruction inline.
								forget(model, prompt.system());
								return send(model, prompt, maxTokens, schema, null);
							}
							return CompletableFuture.completedFuture(response);
						}))
				.thenApply(response -> read(model, response));
	}

	private CompletableFuture<HttpResponse<String>> send(String model, PromptParts prompt, int maxTokens, JsonObject schema,
			String cacheName) {
		JsonObject body = requestBody(prompt, maxTokens, schema, cacheName, strong(model));
		HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/v1/" + locationPath()
						+ "/publishers/google/models/" + model + ":generateContent"))
				.timeout(strong(model) ? STRONG_TIMEOUT : FAST_TIMEOUT)
				.header("Authorization", "Bearer " + token.get())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
	}

	static JsonObject requestBody(PromptParts prompt, int maxTokens, JsonObject schema, String cacheName, boolean strongTier) {
		JsonObject body = new JsonObject();
		JsonArray contents = new JsonArray();
		contents.add(content("user", prompt.user()));
		body.add("contents", contents);
		if (cacheName != null) {
			body.addProperty("cachedContent", cacheName);
		} else {
			body.add("systemInstruction", content(null, prompt.system()));
		}
		JsonObject config = new JsonObject();
		config.addProperty("responseMimeType", "application/json");
		if (schema != null) {
			config.add("responseSchema", schema);
		}
		JsonObject thinking = new JsonObject();
		// The fast tier answers straight off; the strong tier always thinks, so it gets a small allowance on top.
		thinking.addProperty("thinkingBudget", strongTier ? STRONG_THINKING_BUDGET : 0);
		config.add("thinkingConfig", thinking);
		config.addProperty("maxOutputTokens", maxTokens + (strongTier ? STRONG_THINKING_BUDGET : 0));
		body.add("generationConfig", config);
		return body;
	}

	private static JsonObject content(String role, String text) {
		JsonObject part = new JsonObject();
		part.addProperty("text", text);
		JsonArray parts = new JsonArray();
		parts.add(part);
		JsonObject c = new JsonObject();
		if (role != null) {
			c.addProperty("role", role);
		}
		c.add("parts", parts);
		return c;
	}

	private Optional<String> read(String model, HttpResponse<String> response) {
		if (response.statusCode() != 200) {
			throw new IllegalStateException("Vertex AI answered " + response.statusCode() + ": "
					+ response.body().substring(0, Math.min(400, response.body().length())));
		}
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		if (ledger != null && root.has("usageMetadata")) {
			JsonObject u = root.getAsJsonObject("usageMetadata");
			long prompt = count(u, "promptTokenCount");
			long cached = count(u, "cachedContentTokenCount");
			ledger.record(model, prompt - cached, cached, count(u, "candidatesTokenCount") + count(u, "thoughtsTokenCount"));
		}
		return replyText(root);
	}

	/** The answer's text (skipping thoughts), if it finished properly. */
	static Optional<String> replyText(JsonObject root) {
		JsonArray candidates = root.getAsJsonArray("candidates");
		if (candidates == null || candidates.isEmpty()) {
			return Optional.empty();
		}
		JsonObject first = candidates.get(0).getAsJsonObject();
		String finish = first.has("finishReason") ? first.get("finishReason").getAsString() : "STOP";
		if (!"STOP".equals(finish) || !first.has("content")) {
			LOGGER.warn("Gemini reply ended with {}; ignoring it.", finish);
			return Optional.empty();
		}
		StringBuilder text = new StringBuilder();
		JsonArray parts = first.getAsJsonObject("content").getAsJsonArray("parts");
		if (parts != null) {
			for (JsonElement p : parts) {
				JsonObject part = p.getAsJsonObject();
				if (part.has("text") && !(part.has("thought") && part.get("thought").getAsBoolean())) {
					text.append(part.get("text").getAsString());
				}
			}
		}
		return text.isEmpty() ? Optional.empty() : Optional.of(text.toString());
	}

	private static long count(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0;
	}

	// ------------------------------------------------------- context caching

	/** The name of a live cache holding this instruction for this model, or null to send it inline. */
	private String cacheFor(String model, String system) {
		if (!caching) {
			return null;
		}
		CacheEntry entry = caches.computeIfAbsent(model + "\n" + system, k -> new CacheEntry());
		long now = System.currentTimeMillis();
		if (entry.name != null && now < entry.expiresAtMillis - 60_000) {
			return entry.name;
		}
		if (!entry.creating && now >= entry.retryAfterMillis) {
			entry.creating = true;
			createCache(model, system, entry);
		}
		return null;
	}

	private void forget(String model, String system) {
		CacheEntry entry = caches.get(model + "\n" + system);
		if (entry != null) {
			entry.name = null;
		}
	}

	private void createCache(String model, String system, CacheEntry entry) {
		JsonObject body = new JsonObject();
		body.addProperty("model", locationPath() + "/publishers/google/models/" + model);
		body.add("systemInstruction", content(null, system));
		body.addProperty("ttl", cacheTtlSeconds + "s");
		body.addProperty("displayName", "aiciv-" + Integer.toHexString(system.hashCode()));
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(URI.create(apiBase + "/v1/" + locationPath() + "/cachedContents"))
					.timeout(FAST_TIMEOUT)
					.header("Authorization", "Bearer " + token.get())
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
					.build();
		} catch (RuntimeException e) {
			entry.creating = false;
			entry.retryAfterMillis = System.currentTimeMillis() + CACHE_RETRY_MILLIS;
			return;
		}
		client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
			entry.creating = false;
			if (error == null && response.statusCode() == 200) {
				JsonObject o = JsonParser.parseString(response.body()).getAsJsonObject();
				entry.name = o.get("name").getAsString();
				entry.expiresAtMillis = System.currentTimeMillis() + cacheTtlSeconds * 1000;
				LOGGER.info("Vertex AI context cache {} made for {} (for {}s).", entry.name, model, cacheTtlSeconds);
			} else {
				entry.retryAfterMillis = System.currentTimeMillis() + CACHE_RETRY_MILLIS;
				LOGGER.info("Vertex AI wouldn't cache a {} instruction ({}); sending it inline for now.", model,
						error != null ? error.toString() : "HTTP " + response.statusCode() + ": "
								+ response.body().substring(0, Math.min(200, response.body().length())));
			}
		});
	}

	// ---------------------------------------------------------------- schemas

	private static JsonObject type(String t) {
		JsonObject o = new JsonObject();
		o.addProperty("type", t);
		return o;
	}

	private static JsonObject enumOf(String... values) {
		JsonObject o = type("STRING");
		JsonArray e = new JsonArray();
		for (String v : values) {
			e.add(new JsonPrimitive(v));
		}
		o.add("enum", e);
		return o;
	}

	private static JsonObject object(Map<String, JsonObject> properties, String... required) {
		JsonObject o = type("OBJECT");
		JsonObject props = new JsonObject();
		properties.forEach(props::add);
		o.add("properties", props);
		JsonArray req = new JsonArray();
		for (String r : required) {
			req.add(r);
		}
		o.add("required", req);
		JsonArray order = new JsonArray();
		properties.keySet().forEach(order::add);
		o.add("propertyOrdering", order);
		return o;
	}

	private static JsonObject arrayOf(JsonObject items) {
		JsonObject o = type("ARRAY");
		o.add("items", items);
		return o;
	}

	private static <K, V> Map<K, V> ordered(Object... kv) {
		java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			@SuppressWarnings("unchecked") K k = (K) kv[i];
			@SuppressWarnings("unchecked") V v = (V) kv[i + 1];
			m.put(k, v);
		}
		return m;
	}

	static final JsonObject REASONING_SCHEMA = object(ordered(
			"relatedIntent", enumOf(java.util.Arrays.stream(IntentType.values()).map(Enum::name).toArray(String[]::new)),
			"goal", type("STRING"),
			"priority", type("NUMBER"),
			"belief", type("STRING"),
			"beliefConfidence", type("NUMBER"),
			"target", object(ordered("item", type("STRING"), "count", type("INTEGER")), "item", "count")),
			"relatedIntent", "goal", "priority", "belief", "beliefConfidence", "target");

	static final JsonObject DESIGN_SCHEMA = object(ordered(
			"name", type("STRING"),
			"layers", arrayOf(arrayOf(type("STRING")))), "name", "layers");

	static final JsonObject CHAT_SCHEMA = object(ordered("reply", type("STRING"), "remembers", type("STRING")), "reply", "remembers");

	static final JsonObject JOURNAL_SCHEMA = object(ordered("entry", type("STRING")), "entry");

	static final JsonObject STORY_SCHEMA = object(ordered("headline", type("STRING"), "text", type("STRING"), "stands", type("STRING")),
			"headline", "text", "stands");

	static final JsonObject DIALOGUE_SCHEMA = object(ordered(
			"topic", type("STRING"),
			"lines", arrayOf(object(ordered("speaker", enumOf("A", "B"), "text", type("STRING")), "speaker", "text")),
			"a_remembers", type("STRING"),
			"b_remembers", type("STRING"),
			"agreements", arrayOf(object(ordered(
					"type", enumOf("give", "plan", "build_together"),
					"from", enumOf("A", "B"),
					"item", type("STRING"),
					"count", type("INTEGER"),
					"who", enumOf("A", "B", "both"),
					"activity", enumOf("FARM", "FORAGE_FOOD", "GATHER_MATERIALS", "BUILD_SHELTER", "EXPLORE", "SOCIALIZE",
							"GO_HOME", "REST"),
					"goal", type("STRING")), "type"))),
			"topic", "lines", "a_remembers", "b_remembers", "agreements");
}
