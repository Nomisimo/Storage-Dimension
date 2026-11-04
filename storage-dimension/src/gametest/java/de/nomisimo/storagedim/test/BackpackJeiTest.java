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

import org.lwjgl.glfw.GLFW;

/**
 * With JEI and the Traveler's Backpack add-on: are our recipes in JEI (singleplayer and on a dedicated server),
 * does JEI's "+" fill the backpack crafting grid from ME storage (normal and with shift), is "+" red when something is
 * missing, and does the ME view in the backpack window work. Everything is logged as "STORAGEDIM JEI ...".
 */
public class BackpackJeiTest implements FabricClientGameTest {
	private static double hoverX;
	private static double hoverY;

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
		context.waitTicks(5);
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

		// the backpack hotkey is just a letter while typing, and the search can be emptied again
		context.runOnClient(client -> StorageConfig.get().jeiSearchSync = true);
		context.getInput().pressKey(GLFW.GLFW_KEY_B);
		context.waitTicks(5);
		context.runOnClient(client -> log("after B the screen is " + (client.screen instanceof BackpackScreen ? "still the backpack" : client.screen)));

		for (int i = 0; i < 6; i++) {
			context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
		}

		context.waitTicks(10);
		context.runOnClient(client -> {
			String value = client.screen.children().stream()
					.filter(child -> child instanceof net.minecraft.client.gui.components.EditBox)
					.map(child -> ((net.minecraft.client.gui.components.EditBox) child).getValue()).findFirst().orElse("?");
			log("search after clearing: '" + value + "'");
			StorageConfig.get().jeiSearchSync = false;
		});
		context.waitTicks(5);

		// Momo: search, take an item out with the mouse, click back into the field: typing must work again
		context.getInput().typeChars("redst");
		context.waitTicks(10);
		double[] slotAt = context.computeOnClient(client -> screenPos(client, ((BackpackBaseMenu) client.player.containerMenu).BACKPACK_INV_START));
		context.getInput().setCursorPos(slotAt[0], slotAt[1]);
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(5);
		double[] invAt = context.computeOnClient(client -> screenPos(client, client.player.containerMenu.slots.size() - 20));
		context.getInput().setCursorPos(invAt[0], invAt[1]);
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(10);
		context.runOnClient(client -> log("after taking an item: focused=" + client.screen.getFocused() + " carried=" + client.player.containerMenu.getCarried()));
		double[] boxAt = context.computeOnClient(client -> {
			net.minecraft.client.gui.components.EditBox box = client.screen.children().stream()
					.filter(child -> child instanceof net.minecraft.client.gui.components.EditBox)
					.map(child -> (net.minecraft.client.gui.components.EditBox) child).findFirst().orElseThrow();
			double scale = client.getWindow().getGuiScale();
			return new double[] {(box.getX() + box.getWidth() - 4) * scale, (box.getY() + box.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(boxAt[0], boxAt[1]);
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		context.getInput().typeChars("one");
		context.waitTicks(10);
		context.runOnClient(client -> {
			net.minecraft.client.gui.components.EditBox box = client.screen.children().stream()
					.filter(child -> child instanceof net.minecraft.client.gui.components.EditBox)
					.map(child -> (net.minecraft.client.gui.components.EditBox) child).findFirst().orElse(null);
			log("typing after taking an item: '" + (box == null ? "no field" : box.getValue()) + "' focused=" + (box != null && box.isFocused())
					+ " screenFocus=" + client.screen.getFocused());
		});
		context.takeScreenshot("backpack-search-after-take");

		for (int i = 0; i < 12; i++) {
			context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
		}

		context.waitTicks(5);

		// Momo's clicks on an ME slot: click 1, right-click half a stack, shift-click a stack, shift-right-click fills the inventory
		double[] meAt = context.computeOnClient(client -> screenPos(client, ((BackpackBaseMenu) client.player.containerMenu).BACKPACK_INV_START));
		net.minecraft.world.item.Item clicked = context.computeOnClient(client -> client.player.containerMenu.getSlot(((BackpackBaseMenu) client.player.containerMenu).BACKPACK_INV_START).getItem().getItem());
		String[] names = {"click", "right-click", "shift-click", "shift-right-click"};

		for (int i = 0; i < 4; i++) {
			boolean shift = i >= 2;
			int mouse = i % 2 == 0 ? GLFW.GLFW_MOUSE_BUTTON_LEFT : GLFW.GLFW_MOUSE_BUTTON_RIGHT;
			context.getInput().setCursorPos(meAt[0], meAt[1]);

			if (shift) {
				context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
			}

			context.getInput().pressMouse(mouse);

			if (shift) {
				context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
			}

			context.waitTicks(10);
			String name = names[i];
			context.runOnClient(client -> {
				var inventory = client.player.getInventory();
				int main = 0, hotbar = 0, freeMain = 0;

				for (int j = 0; j < 36; j++) {
					var stack = inventory.getItem(j);

					if (stack.is(clicked)) {
						if (j < 9) hotbar += stack.getCount(); else main += stack.getCount();
					} else if (stack.isEmpty() && j >= 9) {
						freeMain++;
					}
				}

				log("ME " + name + " on " + clicked + ": carried=" + client.player.containerMenu.getCarried().getCount()
						+ " inventory=" + main + " hotbar=" + hotbar + " freeMain=" + freeMain);
			});

			if (i == 3) {
				context.takeScreenshot("backpack-me-shift-right-click");
			}

			// put back what is on the cursor
			context.getInput().setCursorPos(meAt[0], meAt[1]);
			if (context.computeOnClient(client -> !client.player.containerMenu.getCarried().isEmpty())) {
				context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
				context.waitTicks(10);
			}
		}

		// everything taken goes back into ME storage for the tests below
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);

			for (int j = 0; j < 36; j++) {
				if (player.getInventory().getItem(j).is(clicked)) {
					StorageApi.insert(player, player.getInventory().getItem(j));
				}
			}

			player.containerMenu.broadcastChanges();
		});
		context.waitTicks(10);

		// a tooltip over the bottom row is drawn above the bar below it
		context.runOnClient(client -> {
			BackpackBaseMenu menu = (BackpackBaseMenu) client.player.containerMenu;
			var bottom = menu.getSlot(menu.BACKPACK_INV_START);

			for (int i = menu.BACKPACK_INV_START; i < menu.BACKPACK_INV_END; i++) {
				var slot = menu.getSlot(i);

				if (slot.isActive() && !slot.getItem().isEmpty() && slot.y >= bottom.y) {
					bottom = slot;
				}
			}

			try {
				Field left = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("leftPos");
				Field top = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("topPos");
				left.setAccessible(true);
				top.setAccessible(true);
				double scale = client.getWindow().getGuiScale();
				hoverX = (left.getInt(client.screen) + bottom.x + 8) * scale;
				hoverY = (top.getInt(client.screen) + bottom.y + 8) * scale;
				log("hovering " + bottom.getItem() + " at " + bottom.x + "," + bottom.y);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
		context.getInput().setCursorPos(hoverX, hoverY);
		context.waitTicks(5);
		context.takeScreenshot("backpack-me-view-hover");
		context.getInput().setCursorPos(0, 0);

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

		// the backpack opens the way it was left: first as backpack, after switching to ME again in the ME view
		reopenBackpack(context, world, "left as backpack");
		context.runOnClient(client -> toggleView());
		context.waitTicks(10);
		context.runOnClient(client -> client.player.closeContainer());
		context.waitTicks(5);
		reopenBackpack(context, world, "left as ME");
		context.takeScreenshot("backpack-reopen-me");
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

	private static void reopenBackpack(ClientGameTestContext context, TestSingleplayerContext world, String label) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			BackpackContainer.openBackpack(player, player.getInventory().getItem(0), Reference.ITEM_SCREEN_ID, 0);
		});
		context.waitFor(client -> client.screen instanceof BackpackScreen, 200);
		context.waitTicks(25);
		context.runOnClient(client -> log("reopen " + label + ": title=" + client.screen.getTitle().getString()));
	}

	/** Where a slot of the open screen is, in window pixels for the test input. Client thread only. */
	private static double[] screenPos(net.minecraft.client.Minecraft client, int slot) {
		try {
			Field left = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("leftPos");
			Field top = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("topPos");
			left.setAccessible(true);
			top.setAccessible(true);
			var s = client.player.containerMenu.getSlot(slot);
			double scale = client.getWindow().getGuiScale();
			return new double[] {(left.getInt(client.screen) + s.x + 8) * scale, (top.getInt(client.screen) + s.y + 8) * scale};
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
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
