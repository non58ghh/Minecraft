package com.aicivilization.reasoning;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Personality;
import java.util.Comparator;
import java.util.List;

/**
 * What an agent's journal entry is written from: who they are, how they're
 * doing, and what they remember since their last entry, in their own words.
 * Only their own memories, never anything they couldn't know.
 */
public record JournalBrief(String name, String temperament, List<String> condition, List<String> remembered) {

	/** At most this many memories go into one entry, the most important since the last. */
	static final int MAX_MEMORIES = 20;

	public JournalBrief {
		condition = List.copyOf(condition);
		remembered = List.copyOf(remembered);
	}

	/** From the agent's own mind: memories since {@code sinceTick}, oldest first. */
	public static JournalBrief of(AgentMind mind, long sinceTick, long nowTick) {
		Personality p = mind.personality();
		List<String> remembered = mind.memories().all().stream()
				.filter(m -> m.tick() > sinceTick)
				.sorted(Comparator.comparingDouble(MemoryEntry::importance).reversed())
				.limit(MAX_MEMORIES)
				.sorted(Comparator.comparingLong(MemoryEntry::tick))
				.map(m -> AgentContext.dayLabel(m.tick(), nowTick) + ": " + m.description())
				.toList();
		var n = mind.needs();
		return new JournalBrief(mind.identity().name(),
				AgentContext.personalityWords(p.curiosity(), p.risk(), p.sociability(), p.ambition()),
				AgentContext.needLines(n.food(), n.safety(), n.social(), n.belonging(), 0, mind.home().isEmpty()),
				remembered);
	}

	static final String RULES = """
			You keep the journal of one of the people of a small, young settlement in a Minecraft world, \
			writing as them, in the first person. Write one entry about their days since the last entry.

			Rules:
			- Use only what they remember below. Don't invent events, places, people or possessions.
			- Write as they would: plain words, their own worries and hopes, what mattered to them and why.
			- Call other people by name. Never use he, she, him, her, his or hers for anyone else: repeat the \
			name or use they.
			- 60 to 140 words, past tense for what happened, present for how they feel now. No headings or lists.

			Reply with JSON only, no other text:
			{"entry": "<the journal entry>"}
			""";

	private String particulars() {
		return "You are " + name + ". " + temperament + "\n\nHow you are now:\n- " + String.join("\n- ", condition)
				+ "\n\nWhat you remember since your last entry (oldest first):\n"
				+ (remembered.isEmpty() ? "- Nothing much happened." : "- " + String.join("\n- ", remembered)) + "\n";
	}

	public String toPrompt() {
		return RULES + "\n" + particulars();
	}

	public PromptParts parts() {
		return new PromptParts(RULES, particulars());
	}
}
