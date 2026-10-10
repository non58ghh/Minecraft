package com.aicivilization.reasoning;

import com.aicivilization.mind.Design;
import com.aicivilization.mind.DesignValidator;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignBriefTest {

	@Test
	void thePromptsExampleIsItselfABuildableHome() {
		JsonArray parsed = JsonParser.parseString(DesignBrief.EXAMPLE).getAsJsonArray();
		List<List<String>> layers = new ArrayList<>();
		for (var layer : parsed) {
			List<String> rows = new ArrayList<>();
			layer.getAsJsonArray().forEach(row -> rows.add(row.getAsString()));
			layers.add(rows);
		}
		Design example = new Design("x", "x", layers);
		assertTrue(DesignValidator.isValid(example), DesignValidator.problem(example).orElse(""));
		assertTrue(example.solids().size() > 100, "the example should show a real house, not a hut");
	}

	@Test
	void thePromptStatesTheCurrentLimits() {
		String prompt = new DesignBrief("Iris", 1, 0.5, 0.5, 0.5, 0.5, List.of()).toPrompt();
		assertTrue(prompt.contains("3 to " + DesignValidator.MAX_SIDE));
		assertTrue(prompt.contains("at most " + DesignValidator.MAX_HEIGHT + " layers"));
		assertTrue(prompt.contains(String.valueOf(DesignValidator.MAX_SOLIDS)));
	}

	@Test
	void aFailedCallIsNotAnEmptyThought() {
		assertTrue(ReasoningResult.failed("the reply was cut off").isFailure());
		assertTrue(!ReasoningResult.none().isFailure());
	}
}
