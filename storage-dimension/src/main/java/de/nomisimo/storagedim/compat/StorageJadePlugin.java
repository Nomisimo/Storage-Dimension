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
	static final Identifier MAILBOX = StorageDimensionMod.id("mailbox");

	@Override
	public void register(IWailaCommonRegistration registration) {
		registration.registerBlockDataProvider(StationData.INSTANCE, RobotStationBlock.class);
		registration.registerBlockDataProvider(MailboxData.INSTANCE, de.nomisimo.storagedim.robot.MailboxBlock.class);
	}

	@Override
	public void registerClient(IWailaClientRegistration registration) {
		registration.registerEntityComponent(RobotInfo.INSTANCE, RobotEntity.class);
		registration.registerBlockComponent(StationInfo.INSTANCE, RobotStationBlock.class);
		registration.registerBlockComponent(MailboxInfo.INSTANCE, de.nomisimo.storagedim.robot.MailboxBlock.class);
	}

	/** A mailbox: its address, where it sends to and how much post waits in the chest beside it. */
	enum MailboxData implements IServerDataProvider<BlockAccessor> {
		INSTANCE;

		@Override
		public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
			StorageData data = StorageData.get();

			if (data == null || !(accessor.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
				return;
			}

			de.nomisimo.storagedim.robot.Mailbox mailbox = data.mailbox(Rooms.id(level), accessor.getPosition());

			if (mailbox != null) {
				tag.putString("Address", mailbox.address());
				tag.putString("Destination", mailbox.destination());
				tag.putInt("Waiting", de.nomisimo.storagedim.robot.Mailbox.waiting(level, accessor.getPosition(), de.nomisimo.storagedim.robot.Mailbox.MAX_STACKS).size());
			}
		}

		@Override
		public Identifier getUid() {
			return MAILBOX;
		}
	}

	enum MailboxInfo implements IBlockComponentProvider {
		INSTANCE;

		@Override
		public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
			CompoundTag data = accessor.getServerData();

			if (!data.contains("Address")) {
				return;
			}

			tooltip.add(Component.literal("Adresse: ").withStyle(ChatFormatting.GRAY).append(Component.literal(data.getStringOr("Address", "")).withStyle(ChatFormatting.WHITE)));
			String destination = data.getStringOr("Destination", "");
			tooltip.add(destination.isEmpty() ? Component.literal("Empfängt nur").withStyle(ChatFormatting.GRAY)
					: Component.literal("Schickt an: ").withStyle(ChatFormatting.GRAY).append(Component.literal(destination).withStyle(ChatFormatting.WHITE)));
			int waiting = data.getIntOr("Waiting", 0);

			if (!destination.isEmpty() && waiting > 0) {
				tooltip.add(Component.literal("Post wartet: " + waiting + (waiting >= de.nomisimo.storagedim.robot.Mailbox.MAX_STACKS ? "+" : "") + " Stapel")
						.withStyle(ChatFormatting.GOLD));
			}
		}

		@Override
		public Identifier getUid() {
			return MAILBOX;
		}
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
				// a station of a group shows its main station
				net.minecraft.core.BlockPos pos = de.nomisimo.storagedim.robot.StationCluster.main(accessor.getLevel(), accessor.getPosition());
				int stations = de.nomisimo.storagedim.robot.StationCluster.members(accessor.getLevel(), pos).size();
				tag.putString("StationName", data.station(dim, pos).stationName());
				tag.putInt("Robots", data.stationRobots(dim, pos).size());
				tag.putInt("MaxRobots", de.nomisimo.storagedim.robot.StationCluster.maxRobots(stations));

				if (stations > 1) {
					tag.putString("Group", (pos.equals(accessor.getPosition()) ? "Hauptstation" : "Erweiterung") + " · Verbund aus " + stations + " Stationen");
				}

				de.nomisimo.storagedim.robot.StationArea area = data.stationArea(dim, pos);

				if (area.connected()) {
					String state = !area.enabled() ? "aus" : de.nomisimo.storagedim.robot.Areas.autoPaused(dim, pos) ? "Pause" : "an";
					tag.putString("Area", (area.name().isEmpty() ? "" : area.name() + " · ") + area.sizeText(pos) + " · " + state);
				} else if (area.hasAntenna()) {
					tag.putString("Area", "1 von 2 Antennen");
				}
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

			String group = data.getStringOr("Group", "");

			if (!group.isEmpty()) {
				tooltip.add(Component.literal(group).withStyle(ChatFormatting.GOLD));
			}

			if (data.contains("Robots")) {
				tooltip.add(Component.literal("Roboter: " + data.getIntOr("Robots", 0) + "/" + data.getIntOr("MaxRobots", StationMenu.MAX_ROBOTS))
						.withStyle(ChatFormatting.GRAY));
			}

			String area = data.getStringOr("Area", "");

			if (!area.isEmpty()) {
				tooltip.add(Component.literal("Area: " + area).withStyle(ChatFormatting.GRAY));
			}
		}

		@Override
		public Identifier getUid() {
			return STATION;
		}
	}
}
