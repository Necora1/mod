package com.necora.aicompanion.client;

import com.necora.aicompanion.entity.CompanionEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.ArmorEntityModel;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.UseAction;
import net.minecraft.util.math.Vec3d;

/**
 * Draws companions exactly like players: player model (wide or slim arms, picked from the skin),
 * armor, held items, crouching and the right arm poses.
 */
public class CompanionRenderer extends EntityRenderer<CompanionEntity> {
	private final PlayerLike wide;
	private final PlayerLike slim;

	public CompanionRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
		this.shadowRadius = 0.5F;
		this.wide = new PlayerLike(ctx, false);
		this.slim = new PlayerLike(ctx, true);
	}

	private PlayerLike pick(CompanionEntity entity) {
		return CompanionSkins.get(entity).model() == SkinTextures.Model.SLIM ? slim : wide;
	}

	@Override
	public void render(CompanionEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		pick(entity).render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
	}

	@Override
	public Identifier getTexture(CompanionEntity entity) {
		return CompanionSkins.get(entity).texture();
	}

	@Override
	public Vec3d getPositionOffset(CompanionEntity entity, float tickDelta) {
		return pick(entity).getPositionOffset(entity, tickDelta);
	}

	static class PlayerLike extends BipedEntityRenderer<CompanionEntity, PlayerEntityModel<CompanionEntity>> {
		PlayerLike(EntityRendererFactory.Context ctx, boolean slimArms) {
			super(ctx, new PlayerEntityModel<>(ctx.getPart(slimArms ? EntityModelLayers.PLAYER_SLIM : EntityModelLayers.PLAYER), slimArms), 0.5F);
			this.addFeature(new ArmorFeatureRenderer<>(this,
					new ArmorEntityModel<>(ctx.getPart(slimArms ? EntityModelLayers.PLAYER_SLIM_INNER_ARMOR : EntityModelLayers.PLAYER_INNER_ARMOR)),
					new ArmorEntityModel<>(ctx.getPart(slimArms ? EntityModelLayers.PLAYER_SLIM_OUTER_ARMOR : EntityModelLayers.PLAYER_OUTER_ARMOR)),
					ctx.getModelManager()));
		}

		@Override
		public Identifier getTexture(CompanionEntity entity) {
			return CompanionSkins.get(entity).texture();
		}

		@Override
		public void render(CompanionEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
			PlayerEntityModel<CompanionEntity> model = this.getModel();
			model.sneaking = entity.isInSneakingPose();
			model.rightArmPose = armPose(entity, Hand.MAIN_HAND);
			model.leftArmPose = armPose(entity, Hand.OFF_HAND);
			if (model.rightArmPose.isTwoHanded()) {
				model.leftArmPose = entity.getOffHandStack().isEmpty() ? BipedEntityModel.ArmPose.EMPTY : BipedEntityModel.ArmPose.ITEM;
			}
			super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
		}

		private static BipedEntityModel.ArmPose armPose(CompanionEntity entity, Hand hand) {
			ItemStack stack = entity.getStackInHand(hand);
			if (stack.isEmpty()) return BipedEntityModel.ArmPose.EMPTY;
			if (entity.isUsingItem() && entity.getActiveHand() == hand && entity.getItemUseTimeLeft() > 0) {
				UseAction action = stack.getUseAction();
				if (action == UseAction.BLOCK) return BipedEntityModel.ArmPose.BLOCK;
				if (action == UseAction.BOW) return BipedEntityModel.ArmPose.BOW_AND_ARROW;
				if (action == UseAction.SPEAR) return BipedEntityModel.ArmPose.THROW_SPEAR;
			}
			return BipedEntityModel.ArmPose.ITEM;
		}

		@Override
		protected void scale(CompanionEntity entity, MatrixStack matrices, float amount) {
			matrices.scale(0.9375F, 0.9375F, 0.9375F);
		}

		@Override
		public Vec3d getPositionOffset(CompanionEntity entity, float tickDelta) {
			return entity.isInSneakingPose() ? new Vec3d(0.0, -0.125, 0.0) : super.getPositionOffset(entity, tickDelta);
		}
	}
}
