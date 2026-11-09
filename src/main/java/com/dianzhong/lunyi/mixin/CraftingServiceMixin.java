package com.dianzhong.lunyi.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.me.service.CraftingService;

import com.dianzhong.lunyi.Config;
import com.dianzhong.lunyi.block.WheelchairCpuBlockEntity;
import com.dianzhong.lunyi.compat.ae2.WheelchairProvider;
import com.google.common.collect.ImmutableSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 AE2 的 CPU 列表里多出一条「轮椅合成核心」。
 * <p>
 * 只有在配置开启、且这张网络上真的有核心方块或停稳的轮椅时才会添加。
 * <p>
 * {@code priority} 设得很高：这个整合包里还有别的模组（data_energistics、ae2omnicells）
 * 也在同一个方法上注入，必须让我们的注入最后执行，否则结果会被它们覆盖。
 */
@Mixin(value = CraftingService.class, priority = 2000)
public class CraftingServiceMixin {
    @Shadow
    @Final
    private IGrid grid;

    @Shadow
    @Final
    private java.util.Set<appeng.me.cluster.implementations.CraftingCPUCluster> craftingCPUClusters;

    /**
     * AE2 每次 CPU 增删都会清空列表并从方块重建，我们的集成方块不在它的扫描结果里，
     * 于是会在合成卡 / ME 请求器触发变更时消失。这里在重建后立刻把它加回去。
     */
    @Inject(method = "updateCPUClusters", at = @At("TAIL"), require = 0, expect = 0)
    private void ae2lunyi(CallbackInfo ci) {
        for (Object cluster : com.dianzhong.lunyi.block.WheelchairCpuBlockEntity.ourClusters()) {
            if (cluster instanceof appeng.me.cluster.implementations.CraftingCPUCluster cpu) {
                this.craftingCPUClusters.add(cpu);
            }
        }
    }

    @Inject(
            method = "getCpus()Lcom/google/common/collect/ImmutableSet;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            expect = 0)
    private void ae2lunyi$addWheelchairCpu(CallbackInfoReturnable<ImmutableSet<ICraftingCPU>> cir) {
        ImmutableSet<ICraftingCPU> original = cir.getReturnValue();
        if (original == null) {
            return;
        }

        boolean hasCore = !this.grid.getMachines(WheelchairCpuBlockEntity.class).isEmpty();
        boolean hasWheelchair = !this.grid.getMachines(WheelchairProvider.class).isEmpty();

        if (!com.dianzhong.lunyi.compat.ae2.WheelchairCpu.LIST_LOGGED) {
            com.dianzhong.lunyi.compat.ae2.WheelchairCpu.LIST_LOGGED = true;
            com.dianzhong.lunyi.ae2lunyi.LOGGER.info(
                    "Wheelchair CPU list check: enabled={}, hasCore={}, hasWheelchair={}, existingCpus={}",
                    Config.WHEELCHAIR_CPU_ENABLED.get(), hasCore, hasWheelchair, original.size());
        }

        if (!Config.WHEELCHAIR_CPU_ENABLED.get() || (!hasCore && !hasWheelchair)) {
            return;
        }

        cir.setReturnValue(ImmutableSet.<ICraftingCPU>builder()
                .addAll(original)
                .add(com.dianzhong.lunyi.compat.ae2.WheelchairCpu.INSTANCE)
                .build());
    }
}
