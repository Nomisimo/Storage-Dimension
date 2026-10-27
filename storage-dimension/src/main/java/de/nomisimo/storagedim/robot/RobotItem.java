package de.nomisimo.storagedim.robot;

import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Placing the item on a block sets a robot down there; it must have been set up at a robot station, which becomes its home. */
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
		RobotConfig config = RobotConfig.read(context.getItemInHand(), level.registryAccess());
		String dim = Rooms.id(level);

		// only robots set up at a station (in this dimension, still standing) can be placed
		if (config == null || config.station().isEmpty() || !config.stationDim().map(dim::equals).orElse(false)
				|| level.isLoaded(config.station().get()) && !level.getBlockState(config.station().get()).is(RobotStationBlock.BLOCK)) {
			if (context.getPlayer() instanceof ServerPlayer player) {
				player.sendSystemMessage(Component.literal(config == null || config.station().isEmpty()
						? "Leg den Roboter erst in eine Roboter-Station, dort wird er eingestellt."
						: "Die Station dieses Roboters steht nicht (mehr) in dieser Welt. Stell ihn an einer Station neu ein.")
						.withStyle(ChatFormatting.RED), true);
			}

			return InteractionResult.FAIL;
		}

		if (StorageData.get().stationRobots(dim, config.station().get()).size() >= StationMenu.MAX_ROBOTS) {
			if (context.getPlayer() instanceof ServerPlayer player) {
				player.sendSystemMessage(Component.literal("Diese Station hat schon " + StationMenu.MAX_ROBOTS + " Roboter.")
						.withStyle(ChatFormatting.RED), true);
			}

			return InteractionResult.FAIL;
		}

		RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);

		if (robot == null) {
			return InteractionResult.FAIL;
		}

		float yaw = context.getPlayer() == null ? 0 : context.getPlayer().getYRot() + 180;
		robot.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0);
		robot.setup(context.getPlayer() == null ? null : context.getPlayer().getUUID(), pos);
		// works around its station and walks there first; keeps the settings it was given there, its name numbered in the station's list
		BlockPos station = config.station().get();
		String name = StationRobots.register(dim, station, robot.getUUID(), config.name());
		robot.applyConfig(config.withName(name));

		level.addFreshEntity(robot);

		if (context.getPlayer() == null || !context.getPlayer().isCreative()) {
			context.getItemInHand().shrink(1);
		}

		return InteractionResult.SUCCESS;
	}
}
