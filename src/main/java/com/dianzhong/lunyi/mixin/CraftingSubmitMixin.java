package com.dianzhong.lunyi.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.service.CraftingService;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.compat.ae2.WheelchairCpu;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把提交给「轮椅合成核心」的合成任务截下来，走我们自己的递归分配。
 * <p>
 * AE2 在 {@code submitJob} 开头就把 target 强转成 {@code CraftingCPUCluster}，
 * 我们的 CPU 会被直接拒，所以必须在 HEAD 处接管。
 * <p>
 * 如果玩家选中了轮椅核心、但样板其实在普通样板供应器上（我们手里没有），
 * 就用 {@code target = null} 重新提交一次，交给 AE2 自己的 CPU 选择流程。
 */
@Mixin(CraftingService.class)
public class CraftingSubmitMixin {
    /** 防止"退回 AE2"时再次进入本注入造成无限递归。 */
    private static final ThreadLocal<Boolean> DELEGATING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Shadow
    @Final
    private IGrid grid;

    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ae2lunyi$takeOver(ICraftingPlan plan, ICraftingRequester requester, ICraftingCPU target,
                                   boolean prioritizePower, IActionSource src,
                                   CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        boolean selectedUs = target instanceof WheelchairCpu
                || com.dianzhong.lunyi.block.WheelchairCpuBlockEntity.isOurCluster(target);
        // 没指定 CPU 时也可以接管，但必须同时满足：
        //  - 这张网络上真的有轮椅合成核心或轮椅；
        //  - 我们的 perform() 能把它做出来（做不出来就原样放行给 AE2）。
        if (!selectedUs) {
            // 配置关闭时只有指定 CPU 才拦截
            if (!com.dianzhong.lunyi.Config.AUTO_INTERCEPT_CRAFTING.get()) {
                return;
            }
            boolean hasCore = !this.grid.getMachines(
                    com.dianzhong.lunyi.block.WheelchairCpuBlockEntity.class).isEmpty();
            boolean hasWheelchair = !this.grid.getMachines(
                    com.dianzhong.lunyi.compat.ae2.WheelchairProvider.class).isEmpty();
            if (!hasCore && !hasWheelchair) {
                return;
            }
        }

        try {
            if (WheelchairCpu.perform(this.grid, plan.finalOutput())) {
                cir.setReturnValue(CraftingSubmitResult.successful(null));
                return;
            }

            // unverifiable recipe: hand over to another CPU, or tell the player when none exists
            if (Boolean.TRUE.equals(WheelchairCpu.UNVERIFIED.get())) {
                boolean hasOtherCpu = this.grid.getCraftingService().getCpus().stream()
                        .anyMatch(cpu -> !(cpu instanceof WheelchairCpu)
                                && !com.dianzhong.lunyi.block.WheelchairCpuBlockEntity.isOurCluster(cpu));
                if (!hasOtherCpu) {
                    if (com.dianzhong.lunyi.Config.NOTIFY_NO_FALLBACK_CPU.get()) {
                        var player = src.player().orElse(null);
                        if (player != null) {
                            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                    "请尝试增加额外的非本模组的CPU"), false);
                        }
                    }
                    ae2lunyi.LOGGER.info(
                            "Wheelchair CPU: no fallback CPU available, telling the player");
                    cir.setReturnValue(CraftingSubmitResult.CPU_TOO_SMALL);
                    return;
                }
            }

            // 样板不是真实配方：直接拒绝整单，不再交给 AE2（否则 AE2 会照样把伪造样板做出来）
            if (Boolean.TRUE.equals(WheelchairCpu.REJECTED.get())) {
                ae2lunyi.LOGGER.info("Wheelchair CPU blocked a job: pattern is not a real recipe");
                cir.setReturnValue(CraftingSubmitResult.CPU_TOO_SMALL);
                return;
            }

            if (selectedUs) {
                if (DELEGATING.get()) {
                    cir.setReturnValue(CraftingSubmitResult.CPU_TOO_SMALL);
                    return;
                }
                DELEGATING.set(Boolean.TRUE);
                try {
                    cir.setReturnValue(this.grid.getCraftingService()
                            .submitJob(plan, requester, null, prioritizePower, src));
                } finally {
                    DELEGATING.remove();
                }
                return;
            }

            // 没选 CPU、我们也做不出来 → 放行给 AE2 原本的逻辑
            ae2lunyi.LOGGER.info("Wheelchair CPU can't craft this, falling back to AE2");
        } catch (Throwable t) {
            ae2lunyi.LOGGER.error("Wheelchair CPU failed to submit a crafting job", t);
            if (selectedUs && !DELEGATING.get()) {
                DELEGATING.set(Boolean.TRUE);
                try {
                    cir.setReturnValue(this.grid.getCraftingService()
                            .submitJob(plan, requester, null, prioritizePower, src));
                } finally {
                    DELEGATING.remove();
                }
            }
        }
    }
}
