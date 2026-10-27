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
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;

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
		state.mood = robot.getMood();
		state.carrying = !robot.getCarried().isEmpty();
		int hash = robot.getUUID().hashCode() * 0x9E3779B9;
		state.idlePhase = (hash >>> 16 & 0xFF) / 255F * 100;
		state.idlePace = 0.8F + (hash >>> 8 & 0xFF) / 255F * 0.4F;
		state.diving = robot.isUnderWater();
		state.umbrella = !robot.isInWater() && robot.level().isRainingAt(robot.blockPosition().above());
		state.postDrop = onPost(robot) ? 0.5F : 0;
		state.y -= state.postDrop;
	}

	/**
	 * Fences, walls and fence gates are 1.5 blocks high for collisions but drawn only one block high. Standing on one,
	 * the robot is drawn half a block lower, so its feet touch the post instead of hovering above it.
	 */
	private static boolean onPost(RobotEntity robot) {
		double y = robot.getY();

		if (!robot.onGround() || Math.abs(y - Math.floor(y) - 0.5) > 0.05) {
			return false;
		}

		var level = robot.level();
		var box = robot.getBoundingBox();
		int below = (int) Math.floor(y) - 1;
		boolean post = false;

		for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1.0E-4); x++) {
			for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1.0E-4); z++) {
				var state = level.getBlockState(new BlockPos(x, below, z));

				if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)) {
					post = true;
				} else if (!state.getCollisionShape(level, new BlockPos(x, below, z)).isEmpty()
						&& state.getCollisionShape(level, new BlockPos(x, below, z)).max(net.minecraft.core.Direction.Axis.Y) > 1) {
					// something else that tall holds it up too: leave it where it is
					return false;
				}
			}
		}

		return post;
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
