package de.nomisimo.storagedim.extra;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;

public final class Extras {
	private Extras() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ExtraConfig.load();
			Performers.load(server);

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Performers.onJoin(player);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> Performers.stop());
		ServerTickEvents.END_SERVER_TICK.register(Performers::tick);
		ServerPlayerEvents.JOIN.register(Performers::onJoin);
		ServerPlayerEvents.LEAVE.register(Performers::onLeave);
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> Performers.onEntityLoad(entity));

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				Performers.isPerformer(entity) ? InteractionResult.FAIL : InteractionResult.PASS);

		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}

			if (Performers.isPerformer(entity)) {
				Performers.tryPayOff(serverPlayer, entity);
				return InteractionResult.SUCCESS;
			}

			if (entity instanceof ServerPlayer target && serverPlayer.isShiftKeyDown() && serverPlayer.getMainHandItem().is(Items.EMERALD)) {
				GiftMenu.open(serverPlayer, target);
				return InteractionResult.SUCCESS;
			}

			return InteractionResult.PASS;
		});

		// Hidden admin reload, only for operators: /storage reload-extras
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
				dispatcher.register(Commands.literal("storage")
						.then(Commands.literal("reload-extras")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(ctx -> {
									ExtraConfig.load();
									return 1;
								}))));
	}
}
