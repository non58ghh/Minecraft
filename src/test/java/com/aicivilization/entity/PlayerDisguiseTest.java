package com.aicivilization.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerDisguiseTest {

	@Test
	void ordinaryNamesPassThrough() {
		assertEquals("Thessaly", PlayerDisguise.profileName("Thessaly"));
	}

	@Test
	void invalidCharactersAreDropped() {
		assertEquals("MaryAnn", PlayerDisguise.profileName("Mary-Ann"));
		assertEquals("Zo", PlayerDisguise.profileName("Zoë"));
	}

	@Test
	void longNamesAreCutTo16() {
		assertEquals("Bartholomewsonvi", PlayerDisguise.profileName("Bartholomewsonvillestein"));
	}

	@Test
	void missingOrUnusableNamesFallBack() {
		assertEquals("Agent", PlayerDisguise.profileName(null));
		assertEquals("Agent", PlayerDisguise.profileName("★★"));
	}
}
