package de.nomisimo.storagedim.robot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Placing the item on a block sets a robot down there; that spot becomes its home. */
public class RobotItem extends Item {
	public RobotItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level)) {
			return InteractionResult.SUCCESS;
		}

		BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
		RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);

		if (robot == null) {
			return InteractionResult.FAIL;
		}

		float yaw = context.getPlayer() == null ? 0 : context.getPlayer().getYRot() + 180;
		robot.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0);
		robot.setup(context.getPlayer() == null ? null : context.getPlayer().getUUID(), pos);
		level.addFreshEntity(robot);

		if (context.getPlayer() == null || !context.getPlayer().isCreative()) {
			context.getItemInHand().shrink(1);
		}

		return InteractionResult.SUCCESS;
	}
}
