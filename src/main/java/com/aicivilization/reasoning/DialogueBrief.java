package com.aicivilization.reasoning;

import java.util.List;

/**
 * Everything the two people in a conversation could draw on, and nothing
 * more: each one's own situation and recent experiences, and how each feels
 * about the other. Built where they meet, so neither learns anything it
 * couldn't have been told face to face.
 */
public record DialogueBrief(Speaker first, Speaker second, String timeOfDay) {

	/** One side of the conversation, as that agent knows itself. */
	public record Speaker(String name, String temperament, String situation, String carrying,
			List<String> recentExperiences, String feelingsTowardOther) {
		public Speaker {
			recentExperiences = List.copyOf(recentExperiences);
		}
	}

	public String toPrompt() {
		return """
				Two people in a small, young settlement in a Minecraft world meet and talk. They live by \
				foraging, farming wheat, hunting, chopping wood and building their own homes; nobody has \
				a set role. Write their conversation: 4 to 6 short lines of natural, plain speech, \
				alternating speakers, starting with A.

				Ground it only in what each of them knows below. Don't invent events, places, people or \
				possessions. They talk about whatever matters most to them right now, as real neighbours \
				would: an ask, a worry, news, a plan, an offer, teasing between friends, or a cold \
				exchange if they dislike each other. Let their temperaments and feelings show.

				Time: %s

				A is %s
				B is %s

				What they agree to really happens, so only offer what they actually carry (use the \
				item ids listed), and only list an agreement both clearly accepted in the lines; a \
				declined offer is not an agreement. A swap is two "give" entries. But every accepted \
				offer and every plan they settle on together ("we'll start tomorrow", "let's chop \
				wood together", "I'll come by your field") must be listed: an agreement left out \
				simply never happens. For a plan both take on, word the goal so it reads right for \
				either of them (e.g. "chop wood together for Hollis's longhouse"). Small talk that \
				settles nothing has an empty list.

				Reply with JSON only, no other text:
				{"topic": "<what they talked about, 3 to 8 words, lowercase, e.g. 'the failing wheat by the river'>",
				 "lines": [{"speaker": "A", "text": "..."}, {"speaker": "B", "text": "..."}],
				 "a_remembers": "<one first-person sentence A will remember from this, naming B>",
				 "b_remembers": "<one first-person sentence B will remember from this, naming A>",
				 "agreements": [
				   {"type": "give", "from": "A", "item": "<item id the giver carries>", "count": 3},
				   {"type": "plan", "who": "A" | "B" | "both", "activity": "FARM" | "FORAGE_FOOD" | "GATHER_MATERIALS" | "BUILD_SHELTER" | "EXPLORE" | "SOCIALIZE" | "GO_HOME" | "REST",
				    "goal": "<the plan in a few words, e.g. 'help Nadia plant wheat by her lodge'>"},
				   {"type": "build_together"}
				 ]}
				""".formatted(timeOfDay, describe(first), describe(second));
	}

	private static String describe(Speaker s) {
		StringBuilder b = new StringBuilder(s.name()).append(". ").append(s.temperament()).append(' ')
				.append(s.situation()).append(' ').append(s.feelingsTowardOther())
				.append(" Carrying: ").append(s.carrying().isEmpty() ? "nothing" : s.carrying()).append('.');
		if (!s.recentExperiences().isEmpty()) {
			b.append(" Lately, in ").append(s.name()).append("'s own words:");
			for (String e : s.recentExperiences()) {
				b.append("\n  - ").append(e);
			}
		}
		return b.toString();
	}
}
