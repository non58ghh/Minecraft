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
import java.security.SecureRandom;
import java.util.Base64;

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

	/**
	 * Master switch for the simulation, toggled with {@code /civ off} and
	 * {@code /civ on}. When false, agents stand still and no reasoning (and
	 * so no LLM API call) runs. Saved here so it survives restarts.
	 */
	public boolean simulationEnabled = true;

	/** Serve the read-only observer web page (see {@code observer} package). */
	public boolean observerEnabled = true;

	public int observerPort = 8080;

	/**
	 * Secret the observer page and API require (as {@code ?t=...}). Generated
	 * and written back to this file on first start if left blank.
	 */
	public String observerToken = "";

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("aicivilization.json");
	}

	/** Writes the current settings back to {@code config/aicivilization.json}. */
	public void save() {
		save(configPath());
	}

	public static ModConfig loadOrCreate() {
		Path path = configPath();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
				if (loaded != null) {
					reader.close();
					if (loaded.ensureObserverToken()) {
						loaded.save(path);
					}
					return loaded;
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Failed to read aicivilization.json; using defaults.", e);
			}
		}
		ModConfig config = new ModConfig();
		config.ensureObserverToken();
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

	/** Fills in a random observer token if none is set; returns whether it did. */
	private boolean ensureObserverToken() {
		if (observerToken != null && !observerToken.isBlank()) {
			return false;
		}
		byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		observerToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return true;
	}

	public String resolveAnthropicApiKey() {
		String value = System.getenv(anthropicApiKeyEnv);
		return value == null ? "" : value;
	}
}
