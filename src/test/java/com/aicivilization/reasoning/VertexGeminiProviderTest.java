package com.aicivilization.reasoning;

import com.aicivilization.mind.IntentType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexGeminiProviderTest {

	private static final String FAST = "gemini-2.5-flash";
	private static final String STRONG = "gemini-2.5-pro";
	private static final String MODELS = "/v1/projects/p1/locations/us-central1/publishers/google/models/";

	/** A request the fake Vertex endpoint received. */
	record Got(String path, String auth, JsonObject body) {
	}

	private HttpServer server;
	private final List<Got> got = new CopyOnWriteArrayList<>();
	/** What to answer, by path: status and body. */
	private final Map<String, Function<JsonObject, Object[]>> answers = new ConcurrentHashMap<>();

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", ex -> {
			JsonObject body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
					.getAsJsonObject();
			String path = ex.getRequestURI().getPath();
			got.add(new Got(path, ex.getRequestHeaders().getFirst("Authorization"), body));
			Object[] a = answers.getOrDefault(path, b -> new Object[] {404, "{}"}).apply(body);
			byte[] out = ((String) a[1]).getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders((Integer) a[0], out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private VertexGeminiProvider provider(boolean caching, SpendLedger ledger) {
		return new VertexGeminiProvider("http://127.0.0.1:" + server.getAddress().getPort(), "p1", "us-central1", FAST, STRONG,
				caching, 3600, () -> "tok", ledger);
	}

	private static String reply(String json, String finish) {
		JsonObject part = new JsonObject();
		part.addProperty("text", json);
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hmm\",\"thought\":true}," + part
				+ "]},\"finishReason\":\"" + finish + "\"}],"
				+ "\"usageMetadata\":{\"promptTokenCount\":1000000,\"cachedContentTokenCount\":0,\"candidatesTokenCount\":100000,"
				+ "\"thoughtsTokenCount\":0}}";
	}

	private static final String REASONED = "{\"relatedIntent\":\"FORAGE_FOOD\",\"goal\":\"find the cows by the river\","
			+ "\"priority\":0.8,\"belief\":\"\",\"beliefConfidence\":0,\"target\":{\"item\":\"\",\"count\":1}}";

	@Test
	void routineThinkingGoesToTheFastTierWithASchemaAndNoThinking(@TempDir Path dir) {
		SpendLedger ledger = new SpendLedger(100, dir.resolve("spend.json"));
		answers.put(MODELS + FAST + ":generateContent", b -> new Object[] {200, reply(REASONED, "STOP")});
		ReasoningResult r = provider(false, ledger).reason(AgentPromptTest.linnea()).join();
		assertEquals(IntentType.FORAGE_FOOD, r.relatedIntent().orElseThrow());
		assertEquals("find the cows by the river", r.goalDescription().orElseThrow());
		Got req = got.get(0);
		assertEquals("Bearer tok", req.auth());
		JsonObject config = req.body().getAsJsonObject("generationConfig");
		assertEquals("application/json", config.get("responseMimeType").getAsString());
		assertTrue(config.getAsJsonObject("responseSchema").getAsJsonObject("properties").has("relatedIntent"));
		assertEquals(0, config.getAsJsonObject("thinkingConfig").get("thinkingBudget").getAsInt());
		assertTrue(req.body().getAsJsonObject("systemInstruction").toString().contains("WHAT YOU CAN DO"));
		String user = req.body().getAsJsonArray("contents").get(0).getAsJsonObject().getAsJsonArray("parts").get(0)
				.getAsJsonObject().get("text").getAsString();
		assertTrue(user.contains("You are Linnea"));
		assertFalse(user.contains("WHAT YOU CAN DO"), "the fixed rules aren't sent twice");
		// 1M input at $0.30 and 100K output at $2.50.
		assertEquals(0.55, ledger.spentThisMonth(), 1e-9);
	}

	@Test
	void conversationsGoToTheStrongTierWithRoomToThink() {
		String dialogue = "{\"topic\":\"the wheat\",\"lines\":[{\"speaker\":\"A\",\"text\":\"Wheat's up.\"},"
				+ "{\"speaker\":\"B\",\"text\":\"Good.\"}],\"a_remembers\":\"I told Bram.\",\"b_remembers\":\"Iris told me.\","
				+ "\"agreements\":[]}";
		answers.put(MODELS + STRONG + ":generateContent", b -> new Object[] {200, reply(dialogue, "STOP")});
		DialogueBrief.Speaker iris = new DialogueBrief.Speaker("Iris", "Calm.", "Fed.", "", List.of(), "Likes Bram.");
		DialogueBrief.Speaker bram = new DialogueBrief.Speaker("Bram", "Bold.", "Hungry.", "", List.of(), "Likes Iris.");
		Dialogue d = provider(false, null).converse(new DialogueBrief(iris, bram, "morning")).join().orElseThrow();
		assertEquals(List.of("Iris: Wheat's up.", "Bram: Good."), d.lines());
		JsonObject config = got.get(0).body().getAsJsonObject("generationConfig");
		assertEquals(VertexGeminiProvider.STRONG_THINKING_BUDGET, config.getAsJsonObject("thinkingConfig").get("thinkingBudget").getAsInt());
		assertTrue(got.get(0).path().endsWith(STRONG + ":generateContent"));
	}

	@Test
	void aCutOffReplyIsNotTaken() {
		answers.put(MODELS + STRONG + ":generateContent", b -> new Object[] {200, reply("{\"headline\":\"Half", "MAX_TOKENS")});
		assertTrue(provider(false, null).narrate(new StoryBrief(List.of("Iris"), List.of("Day 1 · Iris ate."))).join().isEmpty());
	}

	@Test
	void anUnreachableModelFallsBackToTheAgentsOwnSense() {
		answers.put(MODELS + FAST + ":generateContent", b -> new Object[] {403, "{\"error\":{\"code\":403}}"});
		ReasoningResult r = provider(false, null).reason(AgentPromptTest.linnea()).join();
		assertEquals(null, r.failure(), "a heuristic answer, not a failed pass");
	}

	@Test
	void theFixedPartIsCachedOnceVertexWillHoldIt() throws Exception {
		answers.put(MODELS + FAST + ":generateContent", b -> new Object[] {200, reply(REASONED, "STOP")});
		answers.put("/v1/projects/p1/locations/us-central1/cachedContents",
				b -> new Object[] {200, "{\"name\":\"projects/p1/locations/us-central1/cachedContents/42\"}"});
		VertexGeminiProvider p = provider(true, null);
		p.reason(AgentPromptTest.linnea()).join();
		waitFor(() -> got.stream().anyMatch(g -> g.path().endsWith("/cachedContents")));
		Got create = got.stream().filter(g -> g.path().endsWith("/cachedContents")).findFirst().orElseThrow();
		assertEquals("projects/p1/locations/us-central1/publishers/google/models/" + FAST, create.body().get("model").getAsString());
		assertEquals("3600s", create.body().get("ttl").getAsString());
		Thread.sleep(200);
		got.clear();
		p.reason(AgentPromptTest.linnea()).join();
		JsonObject body = got.get(0).body();
		assertEquals("projects/p1/locations/us-central1/cachedContents/42", body.get("cachedContent").getAsString());
		assertFalse(body.has("systemInstruction"));
	}

	@Test
	void aRefusedCacheMeansSendingTheInstructionInline() throws Exception {
		answers.put(MODELS + FAST + ":generateContent", b -> new Object[] {200, reply(REASONED, "STOP")});
		answers.put("/v1/projects/p1/locations/us-central1/cachedContents",
				b -> new Object[] {400, "{\"error\":{\"message\":\"The minimum token count to start caching is 2048.\"}}"});
		VertexGeminiProvider p = provider(true, null);
		p.reason(AgentPromptTest.linnea()).join();
		waitFor(() -> got.stream().anyMatch(g -> g.path().endsWith("/cachedContents")));
		Thread.sleep(200);
		got.clear();
		p.reason(AgentPromptTest.linnea()).join();
		assertEquals(1, got.size(), "no second attempt at the cache yet");
		assertTrue(got.get(0).body().has("systemInstruction"));
	}

	@Test
	void aLostCacheIsDroppedAndTheCallRetriedInline() throws Exception {
		answers.put(MODELS + FAST + ":generateContent", b -> b.has("cachedContent")
				? new Object[] {404, "{\"error\":{\"message\":\"cache not found\"}}"} : new Object[] {200, reply(REASONED, "STOP")});
		answers.put("/v1/projects/p1/locations/us-central1/cachedContents",
				b -> new Object[] {200, "{\"name\":\"projects/p1/locations/us-central1/cachedContents/7\"}"});
		VertexGeminiProvider p = provider(true, null);
		p.reason(AgentPromptTest.linnea()).join();
		waitFor(() -> got.stream().anyMatch(g -> g.path().endsWith("/cachedContents")));
		Thread.sleep(200);
		ReasoningResult r = p.reason(AgentPromptTest.linnea()).join();
		assertEquals(IntentType.FORAGE_FOOD, r.relatedIntent().orElseThrow());
		assertNotEquals(0, got.stream().filter(g -> g.body().has("systemInstruction") && g.path().endsWith(":generateContent")).count());
	}

	@Test
	void everyKindOfCallSplitsItsPromptIntoAFixedPartAndParticulars() {
		StoryBrief story = new StoryBrief(List.of("Iris"), List.of("Day 1 · Iris ate."));
		assertEquals(story.parts().system(), new StoryBrief(List.of("Bram"), List.of("Day 9 · Bram slept.")).parts().system());
		assertTrue(story.parts().user().contains("Iris ate"));
		assertEquals(AgentPromptTest.linnea().promptParts().system(), AgentContext.SHARED_RULES);
		assertFalse(AgentContext.SHARED_RULES.contains("Linnea"));
	}

	private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < until) {
			Thread.sleep(20);
		}
	}
}
