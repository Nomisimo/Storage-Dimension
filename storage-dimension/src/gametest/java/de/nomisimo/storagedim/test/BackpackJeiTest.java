package de.nomisimo.storagedim.test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import com.tiviacz.travelersbackpack.client.screens.BackpackScreen;
import com.tiviacz.travelersbackpack.common.ServerActions;
import com.tiviacz.travelersbackpack.init.ModDataComponents;
import com.tiviacz.travelersbackpack.init.ModItems;
import com.tiviacz.travelersbackpack.inventory.BackpackContainer;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingUpgrade;
import com.tiviacz.travelersbackpack.network.ServerboundActionTagPacket;
import com.tiviacz.travelersbackpack.util.Reference;

import de.nomisimo.storagedim.StorageConfig;
import de.nomisimo.storagedim.api.StorageApi;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.runtime.IJeiRuntime;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * With JEI and the Traveler's Backpack add-on: are our recipes in JEI (singleplayer and on a dedicated server),
 * does JEI's "+" fill the backpack crafting grid from ME storage (normal and with shift), is "+" red when something is
 * missing, and does the ME view in the backpack window work. Everything is logged as "STORAGEDIM JEI ...".
 */
public class BackpackJeiTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (!FabricLoader.getInstance().isModLoaded("jei")) {
			return;
		}

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksDownload();
			waitForJei(context);
			logOwnRecipes(context, "singleplayer");

			if (FabricLoader.getInstance().isModLoaded("storagedim_tb")) {
				backpackTest(context, world);
			}
		}

		TestJeiPlugin.runtime = null;

		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
				TestServerConnection connection = server.connect()) {
			connection.getClientLevel().waitForChunksDownload();
			waitForJei(context);
			logOwnRecipes(context, "server");
		} catch (Exception e) {
			log("server test failed: " + e);
			e.printStackTrace();
		}
	}

	private static void log(String text) {
		System.out.println("STORAGEDIM JEI " + text);
	}

	private static void waitForJei(ClientGameTestContext context) {
		context.waitFor(client -> TestJeiPlugin.runtime != null, 600);
		context.waitTicks(20);
	}

	private static List<RecipeHolder<CraftingRecipe>> craftingRecipes() {
		return TestJeiPlugin.runtime.getRecipeManager().createRecipeLookup(RecipeTypes.CRAFTING).get().toList();
	}

	private static void logOwnRecipes(ClientGameTestContext context, String where) {
		context.runOnClient(client -> {
			List<RecipeHolder<CraftingRecipe>> all = craftingRecipes();
			List<String> own = all.stream().map(holder -> holder.id().identifier().toString())
					.filter(id -> id.startsWith("storagedim")).sorted().toList();
			log("recipes " + where + ": " + all.size() + " crafting, ours " + own.size() + " " + own);
		});
	}

	private static void backpackTest(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			StorageApi.insert(player, new ItemStack(Items.OAK_PLANKS, 300));
			StorageApi.insert(player, new ItemStack(Items.COAL, 20));
			// a few more for the picture of the ME view
			StorageApi.insert(player, new ItemStack(Items.IRON_INGOT, 1234));
			StorageApi.insert(player, new ItemStack(Items.REDSTONE, 5000));
			StorageApi.insert(player, new ItemStack(Items.DIAMOND, 7));
			StorageApi.insert(player, new ItemStack(Items.GLASS, 128));

			ItemStack backpack = new ItemStack(ModItems.STANDARD_TRAVELERS_BACKPACK);
			BackpackWrapper.initializeSize(backpack);
			ItemStack me = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("storagedim_tb:me_upgrade")));
			backpack.set(ModDataComponents.UPGRADES, ItemContainerContents.fromItems(List.of(new ItemStack(ModItems.CRAFTING_UPGRADE), me)));
			player.getInventory().setItem(0, backpack);
			player.getInventory().setSelectedSlot(0);
			player.getInventory().add(new ItemStack(Items.STICK, 2));
			BackpackContainer.openBackpack(player, player.getInventory().getItem(0), Reference.ITEM_SCREEN_ID, 0);
		});
		context.waitFor(client -> client.screen instanceof BackpackScreen, 200);
		// the stored-item list reaches the client every half second
		context.waitTicks(30);
		logState(world, "opened");
		context.takeScreenshot("backpack-crafting");

		transfer(context, "minecraft:crafting_table", false);
		context.waitTicks(10);
		logState(world, "after + crafting_table");
		transfer(context, "minecraft:crafting_table", true);
		context.waitTicks(10);
		logState(world, "after shift+ crafting_table");
		transfer(context, "minecraft:torch", false);
		context.waitTicks(10);
		logState(world, "after + torch");
		transfer(context, "minecraft:diamond_block", false);
		context.takeScreenshot("backpack-after-jei");

		// ME view in place of the backpack slots
		context.runOnClient(client -> {
			client.getToastManager().clear();
			toggleView();
		});
		context.waitTicks(10);
		logClientSlots(context, "view on");
		context.takeScreenshot("backpack-me-view");
		transfer(context, "minecraft:diamond_block", false);
		transfer(context, "minecraft:chest", false);
		context.waitTicks(10);
		logState(world, "view on, after + chest");

		int first = context.computeOnClient(client -> ((BackpackBaseMenu) client.player.containerMenu).BACKPACK_INV_START);
		click(world, first, 0, ContainerInput.PICKUP);
		context.waitTicks(5);
		context.runOnClient(client -> log("carried after taking from ME: " + client.player.containerMenu.getCarried()));
		click(world, first, 0, ContainerInput.PICKUP);
		context.waitTicks(5);
		context.runOnClient(client -> log("carried after putting back: " + client.player.containerMenu.getCarried()));
		click(world, first, 0, ContainerInput.QUICK_MOVE);
		context.waitTicks(5);
		logState(world, "view on, after shift-click on first ME slot");
		context.takeScreenshot("backpack-me-view-2");

		// closing the crafting tab must hide the grid, with and without the ME view
		setCraftingTab(context, false);
		context.waitTicks(10);
		logClientSlots(context, "view on, tab closed");
		context.takeScreenshot("backpack-me-view-tab-closed");
		setCraftingTab(context, true);
		context.waitTicks(10);

		// search above the window: focus the field and type into it like a player
		context.runOnClient(client -> {
			net.minecraft.client.gui.components.EditBox box = client.screen.children().stream()
					.filter(child -> child instanceof net.minecraft.client.gui.components.EditBox)
					.map(child -> (net.minecraft.client.gui.components.EditBox) child).findFirst().orElse(null);
			log("search field " + (box == null ? "missing" : "at " + box.getX() + "," + box.getY()));

			if (box != null) {
				client.screen.setFocused(box);
			}
		});
		context.getInput().typeChars("redst");
		context.waitTicks(10);
		context.waitTicks(5);
		logClientSlots(context, "view on, search redst");
		context.takeScreenshot("backpack-me-view-search");

		context.runOnClient(client -> toggleView());
		context.waitTicks(10);
		logClientSlots(context, "view off");
		logState(world, "view off");
		setCraftingTab(context, false);
		context.waitTicks(10);
		logClientSlots(context, "view off, tab closed");
		context.takeScreenshot("backpack-tab-closed");
		context.runOnClient(client -> client.player.closeContainer());
		context.waitTicks(5);

		// search sync: text in JEI's search bar also searches the ME terminal
		context.runOnClient(client -> StorageConfig.get().jeiSearchSync = true);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			StorageApi.insert(player, new ItemStack(Items.COBBLESTONE, 500));
			StorageApi.openStorage(player);
		});
		context.waitTicks(10);
		context.runOnClient(client -> TestJeiPlugin.runtime.getIngredientFilter().setFilterText("planks"));
		context.waitTicks(10);
		context.takeScreenshot("terminal-jei-sync");
		context.runOnClient(client -> {
			TestJeiPlugin.runtime.getIngredientFilter().setFilterText("");
			StorageConfig.get().jeiSearchSync = false;
			client.player.closeContainer();
		});
		context.waitTicks(5);
	}

	/** Opens or closes the crafting upgrade's tab, as clicking its icon does. */
	private static void setCraftingTab(ClientGameTestContext context, boolean open) {
		context.runOnClient(client -> {
			BackpackBaseMenu menu = (BackpackBaseMenu) client.player.containerMenu;
			menu.getWrapper().getUpgradeManager().getUpgrade(CraftingUpgrade.class).ifPresent(crafting ->
					ServerboundActionTagPacket.create(ServerboundActionTagPacket.UPGRADE_TAB, crafting.getDataHolderSlot(), open, ServerActions.TAB_OPEN));
			log("crafting tab " + (open ? "open" : "close") + ", client slots " + menu.slots.size());
		});
	}

	/** A click as the server gets it from the client. */
	private static void click(TestSingleplayerContext world, int slot, int button, ContainerInput input) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			player.containerMenu.clicked(slot, button, input, player);
			player.containerMenu.broadcastChanges();
		});
	}

	/** Same as clicking the ME upgrade's icon in the backpack window. Client thread only. */
	private static void toggleView() {
		try {
			Class<?> type = Class.forName("de.nomisimo.storagedim.tb.MeNetworking$TogglePayload");
			Field field = type.getDeclaredField("INSTANCE");
			field.setAccessible(true);
			ClientPlayNetworking.send((CustomPacketPayload) field.get(null));
		} catch (ReflectiveOperationException e) {
			log("toggle failed: " + e);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void transfer(ClientGameTestContext context, String id, boolean max) {
		context.runOnClient(client -> {
			IJeiRuntime runtime = TestJeiPlugin.runtime;
			IRecipeManager manager = runtime.getRecipeManager();
			IRecipeCategory<RecipeHolder<CraftingRecipe>> category = manager.getRecipeCategory(RecipeTypes.CRAFTING);
			Optional<RecipeHolder<CraftingRecipe>> recipe = craftingRecipes().stream()
					.filter(holder -> holder.id().identifier().toString().equals(id)).findFirst();

			if (recipe.isEmpty()) {
				log("transfer " + id + ": recipe not in JEI");
				return;
			}

			AbstractContainerMenu menu = client.player.containerMenu;
			IRecipeLayoutDrawable<RecipeHolder<CraftingRecipe>> layout = manager.createRecipeLayoutDrawable(category, recipe.get(),
					runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()).orElseThrow();
			Optional<IRecipeTransferHandler<AbstractContainerMenu, RecipeHolder<CraftingRecipe>>> handler =
					runtime.getRecipeTransferManager().getRecipeTransferHandler(menu, category);

			if (handler.isEmpty()) {
				log("transfer " + id + ": no handler for " + menu.getClass().getSimpleName());
				return;
			}

			IRecipeTransferError check = handler.get().transferRecipe(menu, recipe.get(), layout.getRecipeSlotsView(), client.player, max, false);
			log("transfer " + id + (max ? " (shift)" : "") + " handler=" + handler.get().getClass().getSimpleName()
					+ " check=" + (check == null ? "OK" : check.getType()));

			if (check == null || check.getType() == IRecipeTransferError.Type.COSMETIC) {
				handler.get().transferRecipe(menu, recipe.get(), layout.getRecipeSlotsView(), client.player, max, true);
			}
		});
	}

	private static void logClientSlots(ClientGameTestContext context, String label) {
		context.runOnClient(client -> {
			BackpackBaseMenu menu = (BackpackBaseMenu) client.player.containerMenu;
			StringBuilder text = new StringBuilder();

			for (int i = menu.BACKPACK_INV_START; i < Math.min(menu.BACKPACK_INV_START + 4, menu.BACKPACK_INV_END); i++) {
				text.append(menu.getSlot(i).getClass().getSimpleName()).append('=').append(menu.getSlot(i).getItem()).append(' ');
			}

			text.append("| ").append(menu.slots.size()).append(" slots, after the player inventory:");

			for (int i = menu.PLAYER_HOT_END; i < menu.slots.size(); i++) {
				var slot = menu.getSlot(i);
				text.append(' ').append(slot.getClass().getSimpleName()).append('@').append(slot.x).append(',').append(slot.y)
						.append(slot.isActive() ? "" : "(inactive)").append('=').append(slot.getItem().getCount());
			}

			log("client slots " + label + ": " + text);
		});
	}

	private static void logState(TestSingleplayerContext world, String label) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			StringBuilder grid = new StringBuilder();

			if (player.containerMenu instanceof BackpackBaseMenu menu) {
				menu.getWrapper().getUpgradeManager().getUpgrade(CraftingUpgrade.class).ifPresent(crafting -> {
					if (crafting.craftSlots != null) {
						crafting.craftSlots.getItems().forEach(stack -> grid.append(stack.isEmpty() ? "-" : stack.getCount() + " " + stack.getItem()).append(", "));
					} else {
						grid.append("no grid");
					}
				});
			} else {
				grid.append("menu ").append(player.containerMenu.getClass().getSimpleName());
			}

			log(label + ": grid [" + grid + "] ME planks=" + StorageApi.count(player, new ItemStack(Items.OAK_PLANKS))
					+ " coal=" + StorageApi.count(player, new ItemStack(Items.COAL))
					+ " inventory planks=" + player.getInventory().countItem(Items.OAK_PLANKS)
					+ " sticks=" + player.getInventory().countItem(Items.STICK)
					+ " torches=" + player.getInventory().countItem(Items.TORCH));
		});
	}
}
