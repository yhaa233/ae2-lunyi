package com.dianzhong.lunyi.registry;

import com.dianzhong.lunyi.ae2lunyi;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册表。
 */
public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ae2lunyi.MODID);

    /**
     * 轮椅刷怪蛋。主色取木头的棕色，斑点色取轮胎的深灰。
     */
    public static final DeferredItem<DeferredSpawnEggItem> WHEELCHAIR_SPAWN_EGG =
            ITEMS.register("wheelchair_spawn_egg",
                    () -> new DeferredSpawnEggItem(ModEntities.WHEELCHAIR, 0x8B5A2B, 0x2E2E2E, new Item.Properties()));
}
