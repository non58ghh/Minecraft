package com.aicivilization.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple JSON config at {@code config/aicivilization.json}. Controls whether
 * (and how) the deep-reasoning system calls out to a real LLM provider.
 */
public final class ModConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger("aicivilization");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** {@code "heuristic"} (default, no network) or {@code "anthropic"}. */
	public String llmProvider = "heuristic";

	/** Name of the environment variable holding the Anthropic API key. Never store the key itself here. */
	public String anthropicApiKeyEnv = "ANTHROPIC_API_KEY";

	/** Left blank by default; see https://docs.anthropic.com/en/docs/about-claude/models for current model ids. */
	public String anthropicModel = "";

	public int anthropicMaxTokens = 300;

	/** How often (in ticks) each agent's mind gets a routine deep-reasoning pass. */
	public long reasoningIntervalTicks = 6000;

	public int maxAgents = 64;

	public static ModConfig loadOrCreate() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("aicivilization.json");
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
				if (loaded != null) {
					return loaded;
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Failed to read aicivilization.json; using defaults.", e);
			}
		}
		ModConfig config = new ModConfig();
		config.save(path);
		return config;
	}

	private void save(Path path) {
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to write default aicivilization.json.", e);
		}
	}

	public String resolveAnthropicApiKey() {
		String value = System.getenv(anthropicApiKeyEnv);
		return value == null ? "" : value;
	}
}
