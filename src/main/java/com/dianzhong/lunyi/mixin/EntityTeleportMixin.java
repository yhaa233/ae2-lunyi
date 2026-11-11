package com.dianzhong.lunyi.mixin;

import com.dianzhong.lunyi.entity.WheelchairEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家用 /tp 或其它方式传送时，把他坐着的轮椅一起搬走。
 * <p>
 * 原版只是把乘客从载具上卸下来，或者把载具留在原地；这里在传送完成后把轮椅同步到乘客的新位置。
 */
@Mixin(Entity.class)
public abstract class EntityTeleportMixin {

    @Inject(method = "teleportTo(DDD)V", at = @At("TAIL"), require = 0, expect = 0)
    private void ae2lunyi$moveVehicleAfterTeleport(double x, double y, double z, CallbackInfo ci) {
        ae2lunyi$moveVehicle(x, y, z);
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V",
            at = @At("TAIL"), require = 0, expect = 0)
    private void ae2lunyi$moveVehicleAfterServerTeleport(ServerLevel level, double x, double y, double z,
                                                        float yaw, float pitch, CallbackInfo ci) {
        ae2lunyi$moveVehicle(x, y, z);
    }

    private void ae2lunyi$moveVehicle(double x, double y, double z) {
        Entity self = (Entity) (Object) this;
        if (!(self.getVehicle() instanceof WheelchairEntity wheelchair)) {
            return;
        }
        if (wheelchair.level() != self.level()) {
            return;
        }
        // 直接把轮椅挪到乘客的新位置，保持乘客仍在座位上
        wheelchair.setPos(x, y, z);
        wheelchair.setDeltaMovement(Vec3.ZERO);
    }
}
