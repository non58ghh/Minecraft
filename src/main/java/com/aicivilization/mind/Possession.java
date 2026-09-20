package com.aicivilization.mind;

/** Something an agent considers its own. Item identity is a plain string (e.g. a Minecraft item id) to keep {@code mind} engine-agnostic. */
public record Possession(String itemId, int quantity, long acquiredTick) {
}
