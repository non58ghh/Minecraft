package com.aicivilization.reasoning;

import java.util.List;

/**
 * What the chronicle's writer is given to write up one story: the story's
 * events in order, as the record has them, and nothing else. Built by the
 * observer side from the event log, never from agents' minds, so the
 * write-up can only say what the record shows.
 *
 * @param people names of those involved, in order of appearance
 * @param record one line per event, oldest first, e.g. "Day 10, 10:26 pm · Mabry told Orrin: ...",
 *               with a conversation's lines indented beneath it
 */
public record StoryBrief(List<String> people, List<String> record) {

	public StoryBrief {
		people = List.copyOf(people);
		record = List.copyOf(record);
	}

	public String toPrompt() {
		return """
				You write the chronicle of a small settlement in a Minecraft world. Its people are \
				autonomous agents; now and then a human player passes through. Below is the record of \
				one story from the settlement, in the order it happened. Write it up for the chronicle.

				Rules:
				- Use only what the record says. Don't invent events, places, motives, feelings or \
				outcomes. If the record says someone was lonely or wanted something, you can say so. \
				If it doesn't say how something turned out, don't guess.
				- Tie each thought or plan to what the person then did about it and how that turned out, \
				in the order it happened.
				- "Spent time on: X" means they worked at X for a while, not that X came true. \
				"Concluded: wants to X" is a wish or plan, not a deed.
				- Leave out detail that doesn't move the story along.
				- Call people by name. Never use he, she, him, her, his or hers for anyone: repeat the \
				name or use they.
				- Plain, warm, concrete prose in the past tense, like a small-town paper. No lists, no \
				headings, no quotation unless it's a line from a conversation in the record.

				People: %s

				Record:
				%s

				Reply with JSON only, no other text:
				{"headline": "<6 to 12 words>", "text": "<40 to 110 words>", "stands": "<where things stand now, 4 to 14 words, only what the record shows>"}
				""".formatted(String.join(", ", people), String.join("\n", record));
	}
}
