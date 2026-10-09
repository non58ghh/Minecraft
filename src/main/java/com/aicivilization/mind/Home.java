package com.aicivilization.mind;

/**
 * Where an agent lives: a building it made (or helped make), remembered by
 * position. Plain coordinates and a design so the mind stays engine-agnostic;
 * the agent only knows its own home, as a place it has been.
 *
 * @param x,y,z     the building's origin (floor cell at the middle of the inside)
 * @param design    what was built there, to find the bed and check it still stands
 * @param builtTick when it was finished
 */
public record Home(int x, int y, int z, Design design, long builtTick) {
}
