package com.dianzhong.lunyi.network;

import com.dianzhong.lunyi.ae2lunyi;
import com.dianzhong.lunyi.compat.ae2.Ae2Compat;
import com.dianzhong.lunyi.entity.WheelchairEntity;

import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 网络包注册。
 */
@EventBusSubscriber(modid = ae2lunyi.MODID)
public final class ModNetwork {
    private ModNetwork() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(DismountPayload.TYPE, DismountPayload.STREAM_CODEC,
                        (payload, context) -> {
                            Player player = context.player();
                            if (player.getVehicle() instanceof WheelchairEntity) {
                                player.stopRiding();
                            }
                        })
                .playToServer(OpenTerminalPayload.TYPE, OpenTerminalPayload.STREAM_CODEC,
                        (payload, context) -> {
                            Player player = context.player();
                            if (player.getVehicle() instanceof WheelchairEntity wheelchair) {
                                Ae2Compat.tryOpenTerminal(player, wheelchair);
                            }
                        })
                .playToServer(OpenPatternStorePayload.TYPE, OpenPatternStorePayload.STREAM_CODEC,
                        (payload, context) -> {
                            Player player = context.player();
                            if (player.level().getEntity(payload.entityId()) instanceof WheelchairEntity wheelchair) {
                                player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                                        (id, inv, p) -> new com.dianzhong.lunyi.compat.ae2.PatternStoreMenu(
                                                id, inv, wheelchair.getPatterns(), 0,
                                                () -> Ae2Compat.refreshNearbyCores(wheelchair)),
                                        net.minecraft.network.chat.Component.translatable("block.ae2_lunyi.pattern_store")),
                                        buf -> {
                                            buf.writeBoolean(false);
                                            buf.writeVarInt(wheelchair.getId());
                                            buf.writeVarInt(0);
                                        });
                            }
                        });
    }
}
