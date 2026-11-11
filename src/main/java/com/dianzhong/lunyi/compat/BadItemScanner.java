package com.dianzhong.lunyi.compat;

import com.dianzhong.lunyi.ae2lunyi;

import com.mojang.serialization.DataResult;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 扫描背包与附近容器，找出"组件序列化会抛异常"的物品。
 * <p>
 * 这类物品一旦进入容器同步包就会导致 {@code container_set_content} 编码失败、
 * 玩家被踢下线。命令本身只读，不会修改任何数据。
 */
public final class BadItemScanner {
    private BadItemScanner() {
    }

    public static int scan(Player player) {
        int bad = 0;
        var inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            bad += check(player, inv.getItem(slot), "inventory slot " + slot);
        }

        var level = player.level();
        var origin = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, -2, -4), origin.offset(4, 2, 4))) {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null) {
                continue;
            }
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                bad += check(player, handler.getStackInSlot(slot), "block " + pos.toShortString() + " slot " + slot);
            }
        }

        if (bad == 0) {
            player.displayClientMessage(Component.literal("未发现损坏物品").withStyle(ChatFormatting.GREEN), false);
        } else {
            player.displayClientMessage(
                    Component.literal("发现 " + bad + " 个损坏物品，详见日志").withStyle(ChatFormatting.RED), false);
        }
        return bad;
    }

    private static int check(Player player, ItemStack stack, String where) {
        if (stack.isEmpty()) {
            return 0;
        }
        try {
            DataResult<?> result = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack);
            if (result.error().isPresent()) {
                ae2lunyi.LOGGER.error("Bad item at {}: {} -> {}", where, stack,
                        result.error().get().message());
                return 1;
            }
            return 0;
        } catch (Throwable t) {
            ae2lunyi.LOGGER.error("Bad item at {}: {} ({})", where, stack, t.toString());
            return 1;
        }
    }
}
