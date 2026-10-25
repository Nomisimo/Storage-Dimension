package de.nomisimo.storagedim.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import de.nomisimo.storagedim.StorageConfig;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class StorageCommands {
	/** Invited player to the network owner who invited them. */
	private static final Map<UUID, UUID> INVITES = new HashMap<>();

	private StorageCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("storage")
				.executes(StorageCommands::info)
				.then(Commands.literal("info").executes(StorageCommands::info))
				.then(Commands.literal("raus").executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();

					if (Rooms.isStorage(player.level())) {
						StorageEvents.leaveRoom(player);
					}

					return 1;
				}))
				.then(Commands.literal("me").executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();

					if (!Backpacks.hasLinkedBackpack(player)) {
						send(player, "Du brauchst einen ME-Rucksack (auf dem Rücken oder im Inventar).", ChatFormatting.RED);
						return 0;
					}

					StorageMenu.open(player, StorageData.get().networkFor(player.getUUID()));
					return 1;
				}))
				.then(Commands.literal("teilen")
						.then(Commands.argument("spieler", EntityArgument.player()).executes(StorageCommands::invite)))
				.then(Commands.literal("annehmen").executes(StorageCommands::accept))
				.then(Commands.literal("verlassen").executes(StorageCommands::leaveNetwork))
				.then(Commands.literal("entfernen")
						.then(Commands.argument("spieler", EntityArgument.player()).executes(StorageCommands::kick)))
				.then(Commands.literal("kiste")
						.then(Commands.literal("besitzer")
								.then(Commands.argument("spieler", EntityArgument.player()).executes(ctx -> {
									ServerPlayer player = ctx.getSource().getPlayerOrException();
									ServerPlayer target = EntityArgument.getPlayer(ctx, "spieler");
									StorageEvents.PENDING_OWNER.put(player.getUUID(), target.getUUID());
									send(player, "Klicke jetzt die Dimensionskiste an, die " + name(target) + " gehören soll.", ChatFormatting.YELLOW);
									return 1;
								}))))
				.then(Commands.literal("give")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(ctx -> give(ctx.getSource().getPlayerOrException(), StorageItems.storageChest()))
						.then(Commands.literal("terminal")
								.executes(ctx -> give(ctx.getSource().getPlayerOrException(), StorageItems.terminal())))
						.then(Commands.argument("spieler", EntityArgument.player())
								.executes(ctx -> give(EntityArgument.getPlayer(ctx, "spieler"), StorageItems.storageChest())))));
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		StorageData data = StorageData.get();
		StorageNetwork network = data.networkFor(player.getUUID());
		String owner = network.owner.equals(StorageData.SHARED) ? "allen" : network.owner.equals(player.getUUID()) ? "dir" : playerName(player, network.owner);
		send(player, "Speicher von " + owner + ": " + network.typeCount() + " Sorten, " + network.totalCount() + " Items, "
				+ network.members.size() + " Mitglieder.", ChatFormatting.AQUA);
		send(player, "/storage teilen <spieler> · annehmen · verlassen · entfernen <spieler> · raus · kiste besitzer <spieler>", ChatFormatting.GRAY);
		return 1;
	}

	private static int invite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();

		if (StorageConfig.get().sharedStorage) {
			send(player, "Alle Spieler teilen sich bereits einen gemeinsamen Speicher.", ChatFormatting.YELLOW);
			return 0;
		}
		ServerPlayer target = EntityArgument.getPlayer(ctx, "spieler");
		StorageNetwork network = StorageData.get().networkFor(player.getUUID());

		if (target == player) {
			return 0;
		}

		INVITES.put(target.getUUID(), network.owner);
		send(player, name(target) + " wurde eingeladen.", ChatFormatting.GREEN);
		send(target, name(player) + " möchte den Speicher mit dir teilen. Annehmen mit /storage annehmen", ChatFormatting.GOLD);
		return 1;
	}

	private static int accept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();

		if (StorageConfig.get().sharedStorage) {
			send(player, "Alle Spieler teilen sich bereits einen gemeinsamen Speicher.", ChatFormatting.YELLOW);
			return 0;
		}
		UUID owner = INVITES.remove(player.getUUID());
		StorageData data = StorageData.get();

		if (owner == null || !data.networks.containsKey(owner)) {
			send(player, "Du hast keine offene Einladung.", ChatFormatting.RED);
			return 0;
		}

		StorageNetwork current = data.networkFor(player.getUUID());

		if (!current.owner.equals(player.getUUID())) {
			current.members.remove(player.getUUID());
		}

		StorageNetwork network = data.networks.get(owner);
		network.members.add(player.getUUID());
		data.memberOf.put(player.getUUID(), owner);
		StorageData.markDirty();
		send(player, "Du nutzt jetzt den gemeinsamen Speicher. Dein eigener bleibt erhalten (/storage verlassen).", ChatFormatting.GREEN);
		return 1;
	}

	private static int leaveNetwork(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();

		if (StorageConfig.get().sharedStorage) {
			send(player, "Alle Spieler teilen sich bereits einen gemeinsamen Speicher.", ChatFormatting.YELLOW);
			return 0;
		}
		StorageData data = StorageData.get();
		UUID owner = data.memberOf.remove(player.getUUID());

		if (owner == null) {
			send(player, "Du nutzt bereits deinen eigenen Speicher.", ChatFormatting.RED);
			return 0;
		}

		StorageNetwork network = data.networks.get(owner);

		if (network != null) {
			network.members.remove(player.getUUID());
		}

		StorageData.markDirty();
		send(player, "Du nutzt wieder deinen eigenen Speicher.", ChatFormatting.GREEN);
		return 1;
	}

	private static int kick(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();

		if (StorageConfig.get().sharedStorage) {
			send(player, "Alle Spieler teilen sich bereits einen gemeinsamen Speicher.", ChatFormatting.YELLOW);
			return 0;
		}
		ServerPlayer target = EntityArgument.getPlayer(ctx, "spieler");
		StorageData data = StorageData.get();
		StorageNetwork network = data.ownNetwork(player.getUUID());

		if (!network.members.remove(target.getUUID())) {
			send(player, name(target) + " ist kein Mitglied.", ChatFormatting.RED);
			return 0;
		}

		data.memberOf.remove(target.getUUID());
		StorageData.markDirty();
		send(player, name(target) + " wurde entfernt.", ChatFormatting.GREEN);
		return 1;
	}

	private static int give(ServerPlayer player, ItemStack stack) {

		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}

		return 1;
	}

	private static String name(ServerPlayer player) {
		return player.getGameProfile().name();
	}

	private static String playerName(ServerPlayer anyPlayer, UUID id) {
		ServerPlayer other = anyPlayer.level().getServer().getPlayerList().getPlayer(id);
		return other != null ? name(other) : "einem anderen Spieler";
	}

	static void send(ServerPlayer player, String text, ChatFormatting color) {
		player.sendSystemMessage(Component.literal(text).withStyle(color));
	}
}
