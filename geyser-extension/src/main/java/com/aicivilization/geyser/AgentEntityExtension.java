package com.aicivilization.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.util.Identifier;

/**
 * Makes the AI Civilization mod's agents visible to Bedrock players.
 *
 * <p>Bedrock has no concept of a Fabric-registered entity type, so without
 * this, Geyser silently drops every {@code aicivilization:agent} spawn.
 * This registers one placeholder custom Bedrock entity (see the
 * {@code bedrock-resource-pack/} at the repo root for its model/texture)
 * and redirects spawns of the Java-side agent entity to it. Agent minds,
 * goals, and behavior are entirely unaffected — this only changes what a
 * Bedrock client is told to render.
 *
 * <p>Built against Geyser API 2.11.0, whose Custom Entity API
 * ({@link GeyserDefineEntitiesEvent}, {@link ServerSpawnEntityEvent#definition})
 * is marked {@code @ApiStatus.Experimental} by Geyser itself and may change
 * between releases without deprecation. If this fails to compile against a
 * newer geyser-api, the fix is almost always a renamed method on one of the
 * two event types below, not a design change here.
 */
public final class AgentEntityExtension implements Extension {

	private static final Identifier AGENT_IDENTIFIER = Identifier.of("aicivilization", "agent");

	private final CustomEntityDefinition agentDefinition = CustomEntityDefinition.of(AGENT_IDENTIFIER);

	@Subscribe
	public void onDefineEntities(GeyserDefineEntitiesEvent event) {
		event.register(agentDefinition);
		logger().info("Registered custom Bedrock entity for aicivilization:agent.");
	}

	@Subscribe
	public void onSpawnEntity(ServerSpawnEntityEvent event) {
		if (event.entityType().is(AGENT_IDENTIFIER)) {
			event.definition(agentDefinition);
		}
	}
}
