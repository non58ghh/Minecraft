package com.aicivilization.mind;

import java.util.UUID;

/**
 * A device an agent knows of, and whether it's been seen to work.
 *
 * @param how       "invented" (its own), "saw" (looked at someone's) or "told"
 * @param inventor  who first thought it up
 * @param works     whether trying it did what it was meant to
 * @param observed  what happened when it was tried, in plain words ("the oak door opened")
 */
public record KnownInvention(Invention invention, String how, String inventor, UUID inventorId, long tick, boolean works,
		String observed) {
}
