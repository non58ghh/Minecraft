package com.aicivilization.client;

import com.aicivilization.entity.AgentEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;

/**
 * Placeholder visuals only: reuses the vanilla zombie model/texture so
 * agents are visible in-game without needing new art assets. A distinct
 * look for agents is future work, not a Milestone 1 concern — see
 * DESIGN.md.
 */
public final class AgentRenderer extends MobEntityRenderer<AgentEntity, BipedEntityModel<AgentEntity>> {

	private static final Identifier TEXTURE = Identifier.of("minecraft", "textures/entity/zombie/zombie.png");

	public AgentRenderer(EntityRendererFactory.Context context) {
		super(context, new BipedEntityModel<>(context.getPart(EntityModelLayers.ZOMBIE)), 0.5f);
	}

	@Override
	public Identifier getTexture(AgentEntity entity) {
		return TEXTURE;
	}
}
