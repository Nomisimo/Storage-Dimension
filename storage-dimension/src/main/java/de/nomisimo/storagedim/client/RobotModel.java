package de.nomisimo.storagedim.client;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.robot.RobotEntity;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * The collector robot: a helmet-like head with a beak on a jointed neck, a small body and two legs.
 * Walks with high knees and a bobbing head, bends down and opens the beak to pick things up, and sits when idle.
 */
public class RobotModel extends EntityModel<RobotRenderState> {
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(StorageDimensionMod.id("robot"), "main");

	final ModelPart body;
	final ModelPart neck;
	final ModelPart head;
	final ModelPart jaw;
	private final ModelPart rightLeg;
	private final ModelPart rightShin;
	private final ModelPart rightFoot;
	private final ModelPart leftLeg;
	private final ModelPart leftShin;
	private final ModelPart leftFoot;
	private final ModelPart goggles;
	private final ModelPart umbrella;

	public RobotModel(ModelPart root) {
		super(root);
		body = root.getChild("body");
		neck = body.getChild("neck");
		head = neck.getChild("head");
		jaw = head.getChild("jaw");
		goggles = head.getChild("goggles");
		umbrella = body.getChild("umbrella");
		rightLeg = root.getChild("right_leg");
		rightShin = rightLeg.getChild("shin");
		rightFoot = rightShin.getChild("foot");
		leftLeg = root.getChild("left_leg");
		leftShin = leftLeg.getChild("shin");
		leftFoot = leftShin.getChild("foot");
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
				.texOffs(0, 0).addBox(-3, -2, -3.5F, 6, 5, 7)
				.texOffs(0, 12).addBox(-2.5F, -1, 3.5F, 5, 4, 1), PartPose.offset(0, 14, 0));
		// a little umbrella in the robot's colour (accent texture), held up behind the head when it rains
		body.addOrReplaceChild("umbrella", CubeListBuilder.create()
				.texOffs(48, 78).addBox(-0.5F, -23, 3, 1, 21, 1)
				.texOffs(0, 64).addBox(-6, -23, -7, 12, 1, 12)
				.texOffs(0, 78).addBox(-4, -24, -5, 8, 1, 8)
				.texOffs(32, 78).addBox(-2, -25, -3, 4, 1, 4)
				.texOffs(56, 78).addBox(-0.5F, -26, -1.5F, 1, 1, 1), PartPose.ZERO);
		PartDefinition neck = body.addOrReplaceChild("neck", CubeListBuilder.create()
				.texOffs(26, 0).addBox(-1, -6, -1, 2, 6, 2), PartPose.offset(0, -2, -2));
		PartDefinition head = neck.addOrReplaceChild("head", CubeListBuilder.create()
				.texOffs(0, 18).addBox(-3, -5, -5, 6, 4, 8)
				.texOffs(28, 30).addBox(-3, -2, -6, 6, 1, 1)
				.texOffs(36, 0).addBox(-3.5F, -4.5F, -3.5F, 1, 3, 3)
				.texOffs(36, 6).addBox(2.5F, -4.5F, -3.5F, 1, 3, 3), PartPose.offset(0, -6, 0));
		// a diving helmet: a glass box around the whole head and beak, only worn under water
		head.addOrReplaceChild("goggles", CubeListBuilder.create()
				.texOffs(0, 88).addBox(-4, -6, -7, 8, 7, 11), PartPose.ZERO);
		head.addOrReplaceChild("jaw", CubeListBuilder.create()
				.texOffs(0, 30).addBox(-3, 0, -6, 6, 1, 7), PartPose.offset(0, -1, 0.5F));

		for (int side = -1; side <= 1; side += 2) {
			PartDefinition leg = root.addOrReplaceChild(side < 0 ? "right_leg" : "left_leg", CubeListBuilder.create()
					.texOffs(44, 0).addBox(-1, 0, -1, 2, 3, 2), PartPose.offset(2 * side, 17, 0.5F));
			PartDefinition shin = leg.addOrReplaceChild("shin", CubeListBuilder.create()
					.texOffs(52, 0).addBox(-1, 0, -1, 2, 3, 2), PartPose.offset(0, 3, 0));
			shin.addOrReplaceChild("foot", CubeListBuilder.create()
					.texOffs(44, 6).addBox(-1.5F, 0, -2.5F, 3, 1, 4), PartPose.offset(0, 3, 0));
		}

		return LayerDefinition.create(mesh, 64, 128);
	}

	@Override
	public void setupAnim(RobotRenderState state) {
		super.setupAnim(state);
		float pos = state.walkAnimationPos * 0.9F;
		float speed = Math.min(state.walkAnimationSpeed * 1.4F, 1);
		// each robot has its own offset and pace for the idle animations
		float age = state.ageInTicks * state.idlePace + state.idlePhase;
		goggles.visible = state.diving;
		umbrella.visible = state.umbrella;

		// marching: high knees, feet kept flat, a little waddle and a bobbing head
		float swing = Mth.cos(pos) * 0.9F * speed;
		float rightKnee = Math.max(0, Mth.sin(pos)) * 1.4F * speed;
		float leftKnee = Math.max(0, -Mth.sin(pos)) * 1.4F * speed;
		rightLeg.xRot = -swing - rightKnee * 0.6F;
		rightShin.xRot = rightKnee;
		rightFoot.xRot = -(rightLeg.xRot + rightShin.xRot);
		leftLeg.xRot = swing - leftKnee * 0.6F;
		leftShin.xRot = leftKnee;
		leftFoot.xRot = -(leftLeg.xRot + leftShin.xRot);
		body.y -= Math.abs(Mth.sin(pos)) * 0.8F * speed;
		body.zRot = Mth.sin(pos) * 0.08F * speed;
		neck.xRot = -0.1F + Mth.sin(pos * 2) * 0.12F * speed;

		// looking around, with a curious head tilt when standing still
		neck.yRot = state.yRot * Mth.DEG_TO_RAD * 0.5F;
		head.yRot = state.yRot * Mth.DEG_TO_RAD * 0.5F;
		head.xRot = state.xRot * Mth.DEG_TO_RAD * 0.6F;
		head.zRot = Mth.sin(age * 0.06F) * 0.12F * (1 - speed);

		if (state.sitting) {
			body.y += 4;
			rightLeg.y += 4;
			leftLeg.y += 4;
			rightLeg.xRot = leftLeg.xRot = -1.45F;
			rightShin.xRot = leftShin.xRot = 1.45F;
			rightFoot.xRot = leftFoot.xRot = 0;
			neck.xRot = -0.25F;

			if (state.mood == RobotEntity.MOOD_SAD) {
				// hangs its head and stares at the ground
				neck.xRot = 0.85F;
				head.xRot = 0.45F;
				head.yRot = 0;
				neck.yRot = 0;
				head.zRot = 0.1F;
				body.xRot = 0.15F;
			} else if (state.mood == RobotEntity.MOOD_WATCHING) {
				// called back: sits still and looks at the player who called
				neck.yRot = state.yRot * Mth.DEG_TO_RAD * 0.4F;
				head.yRot = state.yRot * Mth.DEG_TO_RAD * 0.6F;
				head.xRot = state.xRot * Mth.DEG_TO_RAD * 0.8F;
				head.zRot = 0;
			} else if (state.mood == RobotEntity.MOOD_HAPPY) {
				// looks around cheerfully, bobbing its head and wiggling now and then
				head.yRot = Mth.sin(age * 0.12F) * 0.7F;
				neck.yRot = Mth.sin(age * 0.12F) * 0.25F;
				neck.xRot = -0.35F + Mth.sin(age * 0.3F) * 0.1F;
				head.zRot = Mth.sin(age * 0.24F) * 0.15F;
				body.zRot = Mth.sin(age * 0.5F) * 0.05F;
			}
		}

		// bend down, open the beak, close it again
		if (state.action != 0) {
			float bow = Mth.sin(state.actionProgress * Mth.PI);
			body.xRot += bow * 0.35F;
			neck.xRot += bow * (state.action == 1 ? 1.5F : 0.9F);
			head.xRot += bow * 0.4F;
			jaw.xRot = Mth.sin(state.actionProgress * Mth.PI * 2) > 0 ? bow * 0.8F : 0;
			rightLeg.xRot -= bow * 0.3F;
			leftLeg.xRot -= bow * 0.3F;
			rightShin.xRot += bow * 0.5F;
			leftShin.xRot += bow * 0.5F;
		} else if (state.carrying) {
			jaw.xRot = 0.3F;
		}
	}
}
