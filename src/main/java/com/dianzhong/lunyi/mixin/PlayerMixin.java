package com.dianzhong.lunyi.mixin;

import com.dianzhong.lunyi.entity.WheelchairEntity;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 原版 {@code Player#wantsToStopRiding()} 直接返回"是否按住 Shift"，
 * 于是骑马/坐船时按 Shift 就会下车。轮椅不想要这个行为：
 * <ul>
 *   <li>Shift 在轮椅上被用作"下降"，和下车冲突；</li>
 *   <li>脱离只走 R 键（{@code ModKeyMappings.DISMOUNT}）。</li>
 * </ul>
 * 所以骑着轮椅时这里恒返回 false。
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
    private void ae2lunyi$stayOnWheelchair(CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (self.getVehicle() instanceof WheelchairEntity) {
            cir.setReturnValue(false);
        }
    }
}
