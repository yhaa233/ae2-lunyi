package com.dianzhong.lunyi.network;

import com.dianzhong.lunyi.ae2lunyi;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 -> 服务端：Alt + 右键轮椅，打开样板仓界面。
 */
public record OpenPatternStorePayload(int entityId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenPatternStorePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ae2lunyi.MODID, "open_pattern_store"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenPatternStorePayload> STREAM_CODEC =
            StreamCodec.composite(
                    net.minecraft.network.codec.ByteBufCodecs.VAR_INT, OpenPatternStorePayload::entityId,
                    OpenPatternStorePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
