package com.dianzhong.lunyi.mixin;

import com.dianzhong.lunyi.entity.WheelchairEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * 玩家传送时（/tp、传送指令、其它模组的传送）把他坐着的轮椅一起搬走。
 * <p>
 * 原版会先让乘客下车，或者只移动乘客本人，轮椅就被留在原地。这里在所有传送重载的尾部
 * 把轮椅移动到乘客的新位置，并在必要时重新让玩家坐上去。
 */
@Mixin(Entity.class)
public abstract class EntityTeleportMixin {

    /** 玩家可见的简单传送（/tp x y z）。 */
    @Inject(method = "teleportTo(DDD)V", at = @At("TAIL"), require = 0, expect = 0)
    private void ae2lunyi$afterSimpleTeleport(double x, double y, double z, CallbackInfo ci) {
        ae2lunyi$syncVehicle(x, y, z);
    }

    /** 跨维度或带朝向的传送。 */
    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V",
            at = @At("TAIL"), require = 0, expect = 0)
    private void ae2lunyi$afterServerTeleport(ServerLevel level, double x, double y, double z,
                                             float yaw, float pitch, CallbackInfo ci) {
        ae2lunyi$syncVehicle(x, y, z);
    }

    /** 1.21 里 /tp 真正走的那个重载（带 Set&lt;Relative&gt; 与镜头参数）。 */
    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z",
            at = @At("TAIL"), require = 0, expect = 0)
    private void ae2lunyi$afterRelativeTeleport(ServerLevel level, double x, double y, double z,
                                                Set<?> relativeMovements, float yaw, float pitch,
                                                boolean setCamera, CallbackInfoReturnable<Boolean> cir) {
        ae2lunyi$syncVehicle(x, y, z);
    }

    private void ae2lunyi$syncVehicle(double x, double y, double z) {
        Entity self = (Entity) (Object) this;

        // 传送过程中乘客可能已经被下车，先记住原来的轮椅
        WheelchairEntity wheelchair = null;
        if (self.getVehicle() instanceof WheelchairEntity current) {
            wheelchair = current;
        } else if (self instanceof net.minecraft.world.entity.player.Player player
                && player.getPersistentData().contains("ae2_lunyi_last_wheelchair")) {
            var uuid = player.getPersistentData().getUUID("ae2_lunyi_last_wheelchair");
            if (player.level() instanceof ServerLevel serverLevel
                    && serverLevel.getEntity(uuid) instanceof WheelchairEntity saved) {
                wheelchair = saved;
            }
        }
        if (wheelchair == null || wheelchair.level() != self.level()) {
            return;
        }

        wheelchair.setPos(x, y, z);
        wheelchair.setDeltaMovement(Vec3.ZERO);
        wheelchair.setYRot(self.getYRot());
        wheelchair.setXRot(0.0F);

        if (self instanceof net.minecraft.world.entity.player.Player player) {
            player.getPersistentData().putUUID("ae2_lunyi_last_wheelchair", wheelchair.getUUID());
        }

        // 重新坐上去，避免传送后被下车
        if (self.getVehicle() == null) {
            self.startRiding(wheelchair, true);
        }
    }
}
