package de.nomisimo.storagedim.robot;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;

/** The collector robot: a small walking duck-like robot that carries dropped items into ME storage. */
public final class Robots {
	public static final ResourceKey<EntityType<?>> ROBOT_KEY = ResourceKey.create(Registries.ENTITY_TYPE, StorageDimensionMod.id("robot"));
	public static final ResourceKey<Item> ROBOT_ITEM_KEY = ResourceKey.create(Registries.ITEM, StorageDimensionMod.id("robot"));

	public static final EntityType<RobotEntity> ROBOT = Registry.register(BuiltInRegistries.ENTITY_TYPE, ROBOT_KEY,
			EntityType.Builder.of(RobotEntity::new, MobCategory.MISC).sized(0.5F, 0.8F).eyeHeight(0.65F).clientTrackingRange(10).build(ROBOT_KEY));
	public static final Item ROBOT_ITEM = Registry.register(BuiltInRegistries.ITEM, ROBOT_ITEM_KEY,
			new RobotItem(new Item.Properties().stacksTo(16).setId(ROBOT_ITEM_KEY)));

	private Robots() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(ROBOT, RobotEntity.createAttributes());
	}
}
