package com.dianzhong.lunyi.compat.ae2;

import appeng.api.config.FuzzyMode;
import appeng.api.inventories.InternalInventory;

import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * 把轮椅/核心内部的样板列表包装成 AE2 的 {@link InternalInventory}，
 * 让「样板管理终端」能读到这些样板。
 * <p>
 * 只支持读取与追加：终端里不提供抽走样板的操作（样板仍由轮椅自己的界面管理）。
 */
public final class PatternListInventory implements InternalInventory {
    private final List<ItemStack> patterns;

    private final Runnable onChange;

    public PatternListInventory(List<ItemStack> patterns, Runnable onChange) {
        this.patterns = patterns;
        this.onChange = onChange;
    }

    private void changed() {
        if (this.onChange != null) {
            this.onChange.run();
        }
    }

    @Override
    public int size() {
        // 永远多给一格空位，否则样板管理终端里没有地方可以放新样板
        return this.patterns.size() + 1;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= this.patterns.size()) {
            return ItemStack.EMPTY;
        }
        return this.patterns.get(slot);
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        if (slot < 0) {
            return;
        }
        while (this.patterns.size() <= slot) {
            this.patterns.add(ItemStack.EMPTY);
        }
        this.patterns.set(slot, stack);
        this.changed();
    }

    @Override
    public ItemStack removeItems(int amount, ItemStack filter, @Nullable Predicate<ItemStack> predicate) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack simulateRemove(int amount, ItemStack filter, Predicate<ItemStack> predicate) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeSimilarItems(int amount, ItemStack filter, FuzzyMode fuzzyMode,
                                        Predicate<ItemStack> predicate) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack simulateSimilarRemove(int amount, ItemStack filter, FuzzyMode fuzzyMode,
                                           Predicate<ItemStack> predicate) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack addItems(ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!simulate) {
            this.patterns.add(stack.copy());
            this.changed();
        }
        return ItemStack.EMPTY;
    }
}
