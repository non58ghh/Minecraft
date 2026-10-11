package com.aicivilization.reasoning;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/**
 * What the LLM has cost this calendar month (UTC), in US dollars, worked out
 * from the token counts each reply reports and the model's list price. Kept
 * in a small file beside the config, so a restart doesn't forget it; a new
 * month starts from nothing. Against a monthly ceiling it says what may
 * still be spent: everything while under {@link #STORIES_ONLY_FROM} of it,
 * then only the chronicle (the write-ups people read), then nothing.
 */
public final class SpendLedger {

	private static final Logger LOGGER = LoggerFactory.getLogger("aicivilization");
	/** Past this share of the month's ceiling, only chronicle write-ups are made. */
	static final double STORIES_ONLY_FROM = 0.9;

	private final double monthlyCeiling;
	private final Path file;
	private final Supplier<YearMonth> month;
	private YearMonth current;
	private double spent;
	private long calls;

	/** {@code monthlyCeiling} 0 or less: no ceiling, only a tally. {@code file} may be null (not kept). */
	public SpendLedger(double monthlyCeiling, Path file) {
		this(monthlyCeiling, file, () -> YearMonth.now(ZoneOffset.UTC));
	}

	SpendLedger(double monthlyCeiling, Path file, Supplier<YearMonth> month) {
		this.monthlyCeiling = monthlyCeiling;
		this.file = file;
		this.month = month;
		this.current = month.get();
		load();
	}

	/** Cached input is billed at a fraction of the input price; this is the most any listed model charges for it. */
	static final double CACHED_INPUT_SHARE = 0.25;

	/**
	 * List price per million tokens, input and output, by model id; an unknown model is priced as the dearest.
	 * Gemini prices are Vertex AI's standard rates for prompts up to 200K tokens.
	 */
	static double[] pricePerMillion(String model) {
		String m = model == null ? "" : model;
		if (m.startsWith("gemini-2.5-flash-lite")) {
			return new double[] {0.10, 0.40};
		}
		if (m.startsWith("gemini-2.5-flash")) {
			return new double[] {0.30, 2.50};
		}
		if (m.startsWith("gemini-2.5-pro")) {
			return new double[] {1.25, 10.00};
		}
		if (m.startsWith("claude-haiku-5")) {
			return new double[] {0.10, 0.50};
		}
		if (m.startsWith("claude-haiku")) {
			return new double[] {1.00, 5.00};
		}
		if (m.startsWith("claude-sonnet-5")) {
			return new double[] {2.00, 10.00};
		}
		if (m.startsWith("claude-sonnet")) {
			return new double[] {3.00, 15.00};
		}
		if (m.startsWith("claude-opus-5-5")) {
			return new double[] {4.00, 20.00};
		}
		if (m.startsWith("claude-opus")) {
			return new double[] {5.00, 25.00};
		}
		return new double[] {10.00, 50.00};
	}

	static double cost(String model, long inputTokens, long outputTokens) {
		double[] p = pricePerMillion(model);
		return (inputTokens * p[0] + outputTokens * p[1]) / 1_000_000.0;
	}

	/** Adds what one reply cost, from its {@code usage}; a body without one is ignored. */
	public void record(String model, String responseBody) {
		long in;
		long out;
		try {
			JsonObject usage = JsonParser.parseString(responseBody).getAsJsonObject().getAsJsonObject("usage");
			if (usage == null) {
				return;
			}
			in = count(usage, "input_tokens") + count(usage, "cache_creation_input_tokens") + count(usage, "cache_read_input_tokens");
			out = count(usage, "output_tokens");
		} catch (RuntimeException e) {
			return;
		}
		record(model, in, out);
	}

	synchronized void record(String model, long inputTokens, long outputTokens) {
		record(model, inputTokens, 0, outputTokens);
	}

	/** One reply's cost from its token counts: fresh input, input read from a cache, and output (thinking included). */
	public synchronized void record(String model, long inputTokens, long cachedInputTokens, long outputTokens) {
		rollOver();
		spent += cost(model, inputTokens, outputTokens)
				+ cachedInputTokens * pricePerMillion(model)[0] * CACHED_INPUT_SHARE / 1_000_000.0;
		calls++;
		save();
	}

	private static long count(JsonObject usage, String key) {
		JsonElement e = usage.get(key);
		return e == null || e.isJsonNull() ? 0 : e.getAsLong();
	}

	/** Whether anything but the chronicle may still be spent on this month. */
	public synchronized boolean allowsAll() {
		rollOver();
		return monthlyCeiling <= 0 || spent < monthlyCeiling * STORIES_ONLY_FROM;
	}

	/** Whether a chronicle write-up may still be paid for this month. */
	public synchronized boolean allowsStories() {
		rollOver();
		return monthlyCeiling <= 0 || spent < monthlyCeiling;
	}

	public synchronized double spentThisMonth() {
		rollOver();
		return spent;
	}

	public synchronized long callsThisMonth() {
		rollOver();
		return calls;
	}

	public double monthlyCeiling() {
		return monthlyCeiling;
	}

	private void rollOver() {
		YearMonth now = month.get();
		if (!now.equals(current)) {
			current = now;
			spent = 0;
			calls = 0;
		}
	}

	private void load() {
		if (file == null || !Files.exists(file)) {
			return;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (current.toString().equals(o.get("month").getAsString())) {
				spent = o.get("spentUsd").getAsDouble();
				calls = o.has("calls") ? o.get("calls").getAsLong() : 0;
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Couldn't read the LLM spend tally; counting this month from zero.", e);
		}
	}

	private void save() {
		if (file == null) {
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("month", current.toString());
		o.addProperty("spentUsd", spent);
		o.addProperty("calls", calls);
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Couldn't save the LLM spend tally.", e);
		}
	}
}
