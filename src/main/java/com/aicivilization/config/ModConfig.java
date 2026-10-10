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

	/** Replies are asked to stay short; this is only a safety cap and is billed only for what is used. */
	public int anthropicMaxTokens = 150;

	/**
	 * At most how often (in ticks) each agent's mind gets a routine
	 * deep-reasoning pass. A pass is skipped if nothing changed since the
	 * last one (see {@link #reasoningNoveltyThreshold}).
	 */
	public long reasoningIntervalTicks = 6000;

	/**
	 * Minimum ticks between two crisis-triggered passes. An agent thinks once
	 * on entering a crisis (or when a different need becomes critical), not
	 * repeatedly while the same crisis lasts.
	 */
	public long reasoningCrisisCooldownTicks = 1200;

	/** How far any need must move (0..1) for a routine pass to count as "something changed". */
	public double reasoningNoveltyThreshold = 0.1;

	/**
	 * Cap on deep-reasoning passes per agent per real-time day (at 20 TPS);
	 * past it the agent runs on its fast system alone. 0 disables the cap.
	 */
	public int maxReasoningCallsPerAgentPerDay = 200;

	/**
	 * Shortest gap, in ticks, between conversations written out by the LLM
	 * (one API call each), across the whole population. Other conversations
	 * still happen, just without a transcript. 0 turns written conversations off.
	 */
	public int dialogueIntervalTicks = 2400;

	/**
	 * Shortest gap, in ticks, between chronicle stories written up by the LLM
	 * (one API call each), across the whole server. Events are grouped into
	 * stories either way; without a write-up the observer shows a story's
	 * record. Default 2400 (two minutes of play). 0 turns write-ups off;
	 * negative counts as 0.
	 */
	public long storyIntervalTicks = 2400;

	public int maxAgents = 64;

	/**
	 * Founders spawned around the world spawn when the server starts on a
	 * world that has never had an agent (none living or dead), e.g. a freshly
	 * generated world. Never refills a population that died out. Capped by
	 * {@link #maxAgents}. 0 turns it off. Read on startup.
	 */
	public int foundingAgents = 10;

	/**
	 * With an empty stomach (food need at 0) an agent loses one point of
	 * health (of 20) this often, so it can starve to death; a well-fed agent
	 * heals. Default 12000 ticks = 10 minutes, about 3 hours 20 minutes of
	 * running time from an empty stomach to dead. 0 turns starvation off.
	 */
	public long starvationDamageIntervalTicks = 12000;

	/**
	 * Master switch for the simulation, toggled with {@code /civ off} and
	 * {@code /civ on}. When false, agents stand still and no reasoning (and
	 * so no LLM API call) runs. Saved here so it survives restarts.
	 */
	public boolean simulationEnabled = true;

	/**
	 * Daily wall-clock window ({@code HH:mm}) in which agents act and think,
	 * e.g. 18:00 to 00:00. Outside it they rest as if switched off, so no AI
	 * calls are made. Leave either blank to run all day. The window may cross
	 * midnight.
	 */
	public String activeHoursStart = "";

	public String activeHoursEnd = "";

	/** IANA time zone for the active hours, e.g. America/New_York; blank uses the server's zone. */
	public String activeHoursTimeZone = "";

	/** Serve the read-only observer web page (see {@code observer} package). */
	public boolean observerEnabled = true;

	public int observerPort = 8080;

	/**
	 * On Google Compute Engine, also copy the observer's overview, agent list
	 * and recent events to the VM's guest attributes once a minute, so they can
	 * be read through the Compute API without reaching the observer port.
	 */
	public boolean publishToGuestAttributes = true;

	/**
	 * Whether the observer API requires {@link #observerToken}. When false,
	 * anyone who can reach {@link #observerPort} can read the observer page.
	 */
	public boolean observerRequireToken = true;

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
