package com.aicivilization.mind;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Cheap "says nearly the same thing" check for short sentences. */
final class TextSimilarity {

	/** Share of distinct words two statements must have in common to count as a repeat. */
	static final double THRESHOLD = 0.6;

	private TextSimilarity() {
	}

	static boolean similar(String a, String b) {
		Set<String> x = words(a);
		Set<String> y = words(b);
		if (x.isEmpty() || y.isEmpty()) {
			return false;
		}
		Set<String> both = new HashSet<>(x);
		both.retainAll(y);
		Set<String> either = new HashSet<>(x);
		either.addAll(y);
		return (double) both.size() / either.size() >= THRESHOLD;
	}

	private static Set<String> words(String s) {
		Set<String> out = new HashSet<>();
		for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")) {
			if (w.length() > 2) {
				out.add(w);
			}
		}
		return out;
	}
}
