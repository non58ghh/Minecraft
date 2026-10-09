package com.aicivilization.mind;

import java.util.UUID;

/**
 * A home this agent has agreed to build together with one other. Each
 * partner holds its own copy: the site is known to whoever found it, and to
 * the other once they've been told (or have seen it), never by telepathy.
 *
 * @param partner     who it's building with
 * @param partnerName their name, for memories and events
 * @param design      what they agreed to build
 * @param siteKnown   whether this agent knows where it's going up
 * @param x,y,z       the site's origin, when known
 * @param agreedTick  when they agreed
 */
public record Project(UUID partner, String partnerName, Design design, boolean siteKnown, int x, int y, int z,
		long agreedTick) {

	public static Project agreed(UUID partner, String partnerName, Design design, long tick) {
		return new Project(partner, partnerName, design, false, 0, 0, 0, tick);
	}

	public Project at(int sx, int sy, int sz) {
		return new Project(partner, partnerName, design, true, sx, sy, sz, agreedTick);
	}
}
