package com.dianzhong.lunyi.mixin;

import com.dianzhong.lunyi.ae2lunyi;

import appeng.crafting.execution.CraftingCpuLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 探针：确认能 hook 到 AE2 合成 CPU 的执行入口。
 * <p>
 * 目标方法是
 * {@code appeng.crafting.execution.CraftingCpuLogic#executeCrafting(int, CraftingService, IEnergyService, Level)}。
 * 这里暂时只打日志、不改变行为（{@code require = 0}，签名对不上也不会崩，只是不生效）。
 * 确认日志能打出来之后，再把逻辑换成轮椅自己的递归分配。
 */
@Mixin(CraftingCpuLogic.class)
public class CraftingCpuLogicMixin {
    @Inject(method = "executeCrafting", at = @At("HEAD"), require = 0, expect = 0)
    private void ae2lunyi$onExecuteCrafting(CallbackInfoReturnable<Integer> cir) {
        // 探针已关闭：之前每 tick 都打日志，反而把真正的信息淹没了
    }
}
