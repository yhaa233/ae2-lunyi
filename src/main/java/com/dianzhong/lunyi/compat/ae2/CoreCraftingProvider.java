package com.dianzhong.lunyi.compat.ae2;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.block.WheelchairCpuBlockEntity;
import com.dianzhong.lunyi.entity.WheelchairEntity;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 只用于"规划阶段"的样板提供者。
 * <p>
 * AE2 在下单前会先算合成树，这一步只认识网络里的 {@link ICraftingProvider}；
 * 没有它，终端会认为"无法合成"，任务根本不会生成，我们的拦截也就无从触发。
 * <p>
 * 样板来源：核心方块自己的样板仓 + <b>所有维度</b>里已加载的轮椅身上的样板仓。
 */
public final class CoreCraftingProvider implements ICraftingProvider, IActionHost {
    private final WheelchairCpuBlockEntity core;

    public CoreCraftingProvider(WheelchairCpuBlockEntity core) {
        this.core = core;
    }

    /** 每次规划都会调用，频率很低，所以现算不做缓存（样板随时可能在轮椅或方块里变）。 */
    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        List<IPatternDetails> decoded = new ArrayList<>();

        var coreLevel = this.core.getLevel();
        if (coreLevel != null) {
            decodeInto(decoded, this.core.getPatterns(), coreLevel);
        }

        // 跨维度：直接遍历所有已加载的轮椅，用它们各自所属的 Level 解码
        for (WheelchairEntity wheelchair : WheelchairEntity.activeWheelchairs()) {
            var level = wheelchair.level();
            if (level == null) {
                continue;
            }
            decodeInto(decoded, wheelchair.getPatterns(), level);
        }

        ae2lunyi.LOGGER.info("Wheelchair core provider: {} patterns available", decoded.size());
        return decoded;
    }

    private static void decodeInto(List<IPatternDetails> out, List<ItemStack> stacks, net.minecraft.world.level.Level level) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level);
            if (details != null) {
                out.add(details);
            }
        }
    }

    @Override
    public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) {
        IGridNode node = this.getActionableNode();
        // AE2 会反复调用这里；真正的产物由 WheelchairCpu.perform 统一交付，
        // 这里绝不能再往网络插物品，否则每次调用都会白送一份（就是之前的复制漏洞）。
        if (node == null || node.getGrid() == null) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    @Override
    public IGridNode getActionableNode() {
        return this.core.getMainNode().getNode();
    }
}
