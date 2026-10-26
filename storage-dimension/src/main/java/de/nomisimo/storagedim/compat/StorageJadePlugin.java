package de.nomisimo.storagedim.compat;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.robot.RobotEntity;
import de.nomisimo.storagedim.robot.RobotStationBlock;
import de.nomisimo.storagedim.robot.StationMenu;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade (only when installed): looking at a robot shows its station, range, filter and what it is doing (or why it
 * is sad); looking at a robot station shows its name and how many robots belong to it.
 */
@WailaPlugin
public class StorageJadePlugin implements IWailaPlugin {
	static final Identifier ROBOT = StorageDimensionMod.id("robot");
	static final Identifier STATION = StorageDimensionMod.id("robot_station");

	@Override
	public void register(IWailaCommonRegistration registration) {
		registration.registerBlockDataProvider(StationData.INSTANCE, RobotStationBlock.class);
	}

	@Override
	public void registerClient(IWailaClientRegistration registration) {
		registration.registerEntityComponent(RobotInfo.INSTANCE, RobotEntity.class);
		registration.registerBlockComponent(StationInfo.INSTANCE, RobotStationBlock.class);
	}

	enum RobotInfo implements IEntityComponentProvider {
		INSTANCE;

		@Override
		public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
			if (accessor.getEntity() instanceof RobotEntity robot) {
				for (Component line : robot.info()) {
					if (!line.getString().isEmpty()) {
						tooltip.add(line);
					}
				}
			}
		}

		@Override
		public Identifier getUid() {
			return ROBOT;
		}
	}

	enum StationData implements IServerDataProvider<BlockAccessor> {
		INSTANCE;

		@Override
		public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
			StorageData data = StorageData.get();

			if (data != null) {
				String dim = Rooms.id(accessor.getLevel());
				tag.putString("StationName", data.station(dim, accessor.getPosition()).stationName());
				tag.putInt("Robots", data.stationRobots(dim, accessor.getPosition()).size());
			}
		}

		@Override
		public Identifier getUid() {
			return STATION;
		}
	}

	enum StationInfo implements IBlockComponentProvider {
		INSTANCE;

		@Override
		public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
			CompoundTag data = accessor.getServerData();
			String name = data.getStringOr("StationName", "");

			if (!name.isEmpty()) {
				tooltip.add(Component.literal(name).withStyle(ChatFormatting.WHITE));
			}

			if (data.contains("Robots")) {
				tooltip.add(Component.literal("Roboter: " + data.getIntOr("Robots", 0) + "/" + StationMenu.MAX_ROBOTS).withStyle(ChatFormatting.GRAY));
			}
		}

		@Override
		public Identifier getUid() {
			return STATION;
		}
	}
}
