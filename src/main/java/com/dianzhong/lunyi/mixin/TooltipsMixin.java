package com.dianzhong.lunyi.mixin;

import appeng.core.localization.Tooltips;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 AE2 的数字显示在"天文数字"时显示成 ∞。
 * <p>
 * 轮椅合成 CPU 的容量/并行返回的是 {@code Long.MAX_VALUE} / {@code Integer.MAX_VALUE}，
 * 原版会显示成 "8E"、"2,147,470" 这种。这里把超过阈值的数统一改写成 "∞"。
 * 阈值取 10^15，正常物品数量永远到不了，所以不会影响别的界面。
 */
@Mixin(Tooltips.class)
public class TooltipsMixin {
    private static final long AE2LUNYI_INFINITE_THRESHOLD = 1_000_000_000_000L;

    @Inject(method = "getAmount(J)Lappeng/core/localization/Tooltips$Amount;",
            at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private static void ae2lunyi$amount(long amount, CallbackInfoReturnable<Tooltips.Amount> cir) {
        if (amount >= AE2LUNYI_INFINITE_THRESHOLD) {
            cir.setReturnValue(new Tooltips.Amount("∞", ""));
        }
    }

    @Inject(method = "getByteAmount(J)Lappeng/core/localization/Tooltips$Amount;",
            at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private static void ae2lunyi$byteAmount(long amount, CallbackInfoReturnable<Tooltips.Amount> cir) {
        if (amount >= AE2LUNYI_INFINITE_THRESHOLD) {
            cir.setReturnValue(new Tooltips.Amount("∞", ""));
        }
    }

    @Inject(method = "ofNumber(J)Lnet/minecraft/network/chat/MutableComponent;",
            at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private static void ae2lunyi$ofNumber(long number, CallbackInfoReturnable<MutableComponent> cir) {
        if (number >= AE2LUNYI_INFINITE_THRESHOLD) {
            cir.setReturnValue(Component.literal("∞"));
        }
    }

    @Inject(method = "ofBytes(J)Lnet/minecraft/network/chat/MutableComponent;",
            at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private static void ae2lunyi$ofBytes(long number, CallbackInfoReturnable<MutableComponent> cir) {
        if (number >= AE2LUNYI_INFINITE_THRESHOLD) {
            cir.setReturnValue(Component.literal("∞"));
        }
    }
}
