package com.aicivilization.reasoning;

/**
 * A prompt in two parts: {@code system}, the same for every call of its kind
 * (how the world works, the rules, the shape of the reply), so a provider
 * can cache it; and {@code user}, this one call's particulars.
 */
public record PromptParts(String system, String user) {
}
