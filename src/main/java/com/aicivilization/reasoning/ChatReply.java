package com.aicivilization.reasoning;

/** What an agent says back to a player, and what it takes away from the exchange. */
public record ChatReply(String reply, String remembers) {
}
