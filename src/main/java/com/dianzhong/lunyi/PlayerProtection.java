package com.dianzhong.lunyi;

import com.dianzhong.lunyi.entity.WheelchairEntity;

import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 坐在轮椅上的人同样无敌：进入伤害结算之前直接取消。
 */
@EventBusSubscriber(modid = ae2lunyi.MODID)
public final class PlayerProtection {
    private PlayerProtection() {}

    @SubscribeEvent
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Player player && player.getVehicle() instanceof WheelchairEntity) {
            event.setCanceled(true);
        }
    }
}
