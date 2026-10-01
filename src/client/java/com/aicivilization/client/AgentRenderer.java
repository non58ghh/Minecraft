package com.aicivilization.client;

import com.aicivilization.entity.AgentEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/**
 * Placeholder visuals only: reuses the vanilla zombie model/texture so
 * agents are visible in-game without needing new art assets. A distinct
 * look for agents is future work, not a Milestone 1 concern — see
 * DESIGN.md.
 */
public final class AgentRenderer extends MobRenderer<AgentEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {

	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/zombie/zombie.png");

	public AgentRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5f);
	}

	@Override
	public HumanoidRenderState createRenderState() {
		return new HumanoidRenderState();
	}

	@Override
	public Identifier getTextureLocation(HumanoidRenderState state) {
		return TEXTURE;
	}
}
