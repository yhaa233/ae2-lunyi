package com.dianzhong.lunyi.compat;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.registry.ModItems;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * 修复历史版本生成的轮椅生成蛋。
 * <p>
 * 早期版本把状态写进 {@code ENTITY_DATA} 时漏了 {@code id} 字段，导致原版在保存玩家数据时
 * 抛 "Missing id for entity" 并崩溃。这里在玩家登录时扫描背包，把缺 id 的补上。
 */
public final class EggRepair {
    private EggRepair() {
    }

    public static int repair(Player player) {
        int fixed = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.is(ModItems.WHEELCHAIR_SPAWN_EGG.get())) {
                continue;
            }
            CustomData data = stack.get(DataComponents.ENTITY_DATA);
            if (data == null) {
                continue;
            }
            CompoundTag tag = data.copyTag();
            if (tag.contains("id")) {
                continue;
            }
            var typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(
                    com.dianzhong.lunyi.registry.ModEntities.WHEELCHAIR.get());
            if (typeKey == null) {
                continue;
            }
            tag.putString("id", typeKey.toString());
            stack.set(DataComponents.ENTITY_DATA, CustomData.of(tag));
            inventory.setItem(slot, stack);
            fixed++;
        }
        if (fixed > 0) {
            ae2lunyi.LOGGER.info("Repaired {} wheelchair spawn egg(s) with missing entity id", fixed);
        }
        return fixed;
    }
}
