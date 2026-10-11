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

	/** {@code "heuristic"} (default, no network), {@code "anthropic"}, or {@code "vertex"} (Gemini on Google Cloud). */
	public String llmProvider = "heuristic";

	/**
	 * With {@code llmProvider} {@code "vertex"}: the Google Cloud project to bill. Blank: the project of the VM
	 * the server runs on. Calls authenticate as the VM's service account, which needs the Vertex AI User role
	 * and the cloud-platform access scope.
	 */
	public String vertexProject = "";

	/** The Vertex AI region (or {@code global}). Default us-central1. */
	public String vertexLocation = "us-central1";

	/** The fast tier: agents' routine thinking, home designs, answers to players. Default gemini-2.5-flash. */
	public String vertexFastModel = "gemini-2.5-flash";

	/** The strong tier: agents' conversations and chronicle write-ups, deaths among them. Default gemini-2.5-pro. */
	public String vertexStrongModel = "gemini-2.5-pro";

	/** Keep each kind of call's fixed instructions in a Vertex AI context cache. Default true. */
	public boolean vertexContextCaching = true;

	/** How long a context cache lives before it's made afresh. Default 3600. */
	public long vertexCacheTtlSeconds = 3600;

	/** Name of the environment variable holding the Anthropic API key. Never store the key itself here. */
	public String anthropicApiKeyEnv = "ANTHROPIC_API_KEY";

	/** Left blank by default; see https://docs.anthropic.com/en/docs/about-claude/models for current model ids. */
	public String anthropicModel = "";

	/**
	 * The model for what people read: agents' written conversations, answers
	 * to players, and chronicle write-ups. Blank: {@link #anthropicModel}
	 * does those too. {@code anthropicModel} handles agents' routine thinking
	 * and home designs, which are many and small, so a cheap model suits it.
	 */
	public String anthropicStrongModel = "";

	/**
	 * Most to spend on the LLM per calendar month (UTC), in US dollars,
	 * reckoned from each reply's token counts at list price. Past 90% of it
	 * only chronicle write-ups are made; at it, nothing until the month
	 * turns. The tally is kept in {@code config/aicivilization-spend.json}.
	 * 0 removes the ceiling. Default 120.
	 */
	public double monthlyBudgetUsd = 120;

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
	 * Ceiling on LLM API calls per real-time hour, across agents' thinking,
	 * written conversations and chronicle write-ups. Agents' own thinking
	 * comes first; conversations and write-ups are made only while at least
	 * half the hour's calls are left. Past it, agents run on their fast
	 * system alone until calls refill (steadily, over the hour). 0 removes
	 * the ceiling. Default 40.
	 */
	public int maxApiCallsPerHour = 40;

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

	/**
	 * Whether zombies, skeletons, spiders and creepers hunt agents as they
	 * hunt players. Default true. False leaves agents off their lists, as in
	 * vanilla (they can still be hurt by accident). Applies to monsters as
	 * they load, so a change takes effect after a restart.
	 */
	public boolean monstersHuntAgents = true;

	public int maxAgents = 64;

	/**
	 * Whether newcomers wander in now and then from far away (one, sometimes
	 * two or three), at unpredictable intervals of a few hours to a couple
	 * of weeks of game time, while the living number fewer than
	 * {@link #maxAgents}. Default true.
	 */
	public boolean wanderers = true;

	/**
	 * Whether two agents who are fond of each other, living together at a
	 * home, fed and unafraid, may now and then have a child, who grows up
	 * over twelve game days and depends on them meanwhile. Default true.
	 */
	public boolean children = true;

	/**
	 * Founders spawned around the world spawn when the server starts on a
	 * world that has never had an agent (none living or dead), e.g. a freshly
	 * generated world. Never refills a population that died out. Capped by
	 * {@link #maxAgents}. 0 turns it off. Read on startup.
	 */
	public int foundingAgents = 10;

	/**
	 * Founders are scattered at random within this many blocks of the world
	 * spawn, each at least {@link #foundingApart} from the others, on dry
	 * land: they start out alone, and whether and when they meet is left to
	 * chance. 0 founds them together at the spawn. Default 120.
	 */
	public int foundingSpread = 120;

	/** The least distance between two founders when scattered. Default 25. */
	public int foundingApart = 25;

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
	 * Keep a public, read-only copy of the observer page in S3-compatible
	 * object storage (a Cloudflare R2 bucket served on your domain), so
	 * people can watch without ever reaching this server. Needs
	 * {@link #publicSiteBucket}, {@link #publicSiteAccountId} (or
	 * {@link #publicSiteEndpoint}) and a key pair: from the environment
	 * variables named below or, on Google Compute Engine, from the instance
	 * metadata values {@code aiciv-site-access-key-id} and
	 * {@code aiciv-site-secret-access-key}. Default false.
	 */
	public boolean publicSiteEnabled = false;

	/** The Cloudflare account id: the endpoint is {@code https://<id>.r2.cloudflarestorage.com}. */
	public String publicSiteAccountId = "";

	/** Any other S3-compatible endpoint, in place of the R2 one. Blank: use {@link #publicSiteAccountId}. */
	public String publicSiteEndpoint = "";

	/** The bucket the public domain serves. */
	public String publicSiteBucket = "";

	/** The signing region: {@code auto} for R2. */
	public String publicSiteRegion = "auto";

	/** How often (seconds) the live data is uploaded; people's pages go every third time. Default 20. */
	public int publicSiteEverySeconds = 20;

	/** Environment variable holding the storage access key id. Never store the key itself here. */
	public String publicSiteAccessKeyEnv = "AICIV_SITE_ACCESS_KEY_ID";

	/** Environment variable holding the storage secret access key. Never store the key itself here. */
	public String publicSiteSecretKeyEnv = "AICIV_SITE_SECRET_ACCESS_KEY";

	/** The storage endpoint to use, or blank if none is set. */
	public String resolvePublicSiteEndpoint() {
		if (publicSiteEndpoint != null && !publicSiteEndpoint.isBlank()) {
			return publicSiteEndpoint.strip();
		}
		return publicSiteAccountId == null || publicSiteAccountId.isBlank() ? ""
				: "https://" + publicSiteAccountId.strip() + ".r2.cloudflarestorage.com";
	}

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
