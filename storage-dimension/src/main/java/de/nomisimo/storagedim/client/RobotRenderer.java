package de.nomisimo.storagedim.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.robot.RobotEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HoldingEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

public class RobotRenderer extends MobRenderer<RobotEntity, RobotRenderState, RobotModel> {
	private static final Identifier TEXTURE = StorageDimensionMod.id("textures/entity/robot.png");
	private static final Identifier ACCENT = StorageDimensionMod.id("textures/entity/robot_accent.png");
	private static final float SCALE = 0.55F;

	public RobotRenderer(EntityRendererProvider.Context context) {
		super(context, new RobotModel(context.bakeLayer(RobotModel.LAYER)), 0.3F);
		addLayer(new AccentLayer(this));
		addLayer(new BeakItemLayer(this));
	}

	@Override
	public RobotRenderState createRenderState() {
		return new RobotRenderState();
	}

	@Override
	public void extractRenderState(RobotEntity robot, RobotRenderState state, float partialTick) {
		super.extractRenderState(robot, state, partialTick);
		HoldingEntityRenderState.extractHoldingEntityRenderState(robot, state, itemModelResolver);
		state.color = robot.getColor();
		state.action = robot.getAction();
		state.actionProgress = robot.getActionProgress();
		state.sitting = robot.isSitting();
		state.carrying = !robot.getCarried().isEmpty();
	}

	@Override
	public Identifier getTextureLocation(RobotRenderState state) {
		return TEXTURE;
	}

	@Override
	protected void scale(RobotRenderState state, PoseStack poseStack) {
		poseStack.scale(SCALE, SCALE, SCALE);
	}

	/** Beak, rim and feet in the robot's dye colour. */
	private static class AccentLayer extends RenderLayer<RobotRenderState, RobotModel> {
		AccentLayer(RenderLayerParent<RobotRenderState, RobotModel> parent) {
			super(parent);
		}

		@Override
		public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, RobotRenderState state, float yRot, float xRot) {
			renderColoredCutoutModel(getParentModel(), ACCENT, poseStack, collector, light, state, 0xFF000000 | state.color, 1);
		}
	}

	/** The carried stack, held crosswise in the beak. */
	private static class BeakItemLayer extends RenderLayer<RobotRenderState, RobotModel> {
		BeakItemLayer(RenderLayerParent<RobotRenderState, RobotModel> parent) {
			super(parent);
		}

		@Override
		public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, RobotRenderState state, float yRot, float xRot) {
			if (state.heldItem.isEmpty()) {
				return;
			}

			RobotModel model = getParentModel();
			poseStack.pushPose();
			model.root().translateAndRotate(poseStack);
			model.body.translateAndRotate(poseStack);
			model.neck.translateAndRotate(poseStack);
			model.head.translateAndRotate(poseStack);
			model.jaw.translateAndRotate(poseStack);
			poseStack.translate(0, -0.02F, -0.42F);
			poseStack.mulPose(Axis.XP.rotationDegrees(90));
			poseStack.scale(0.7F, 0.7F, 0.7F);
			state.heldItem.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
			poseStack.popPose();
		}
	}
}
