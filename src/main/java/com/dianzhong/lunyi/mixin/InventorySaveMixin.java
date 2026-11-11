package com.dianzhong.lunyi.mixin;

import com.dianzhong.lunyi.ae2lunyi;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 背包保存的兜底。
 * <p>
 * 某些物品（例如组件数据不完整、附魔引用失效、ENTITY_DATA 缺 id）在 {@code ItemStack.save}
 * 时会抛异常，原版会让整个存档保存失败并崩溃。这里改成逐个物品保存，坏的那个跳过并记日志，
 * 其余物品正常存档。
 */
@Mixin(Inventory.class)
public abstract class InventorySaveMixin {

    @Inject(method = "save", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ae2lunyi$safeSave(ListTag tag, CallbackInfoReturnable<ListTag> cir) {
        Inventory inventory = (Inventory) (Object) this;
        ListTag result = new ListTag();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putByte("Slot", (byte) slot);
            try {
                result.add(stack.save(inventory.player.level().registryAccess(), entry));
            } catch (Throwable t) {
                ae2lunyi.LOGGER.error(
                        "Skipping un-saveable item in slot {} ({}): {}", slot, stack, t.getMessage());
            }
        }
        cir.setReturnValue(result);
    }
}
