package com.aicivilization.client;

import com.aicivilization.AICivilizationMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public final class AICivilizationClientMod implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(AICivilizationMod.AGENT_ENTITY_TYPE, AgentRenderer::new);
	}
}
